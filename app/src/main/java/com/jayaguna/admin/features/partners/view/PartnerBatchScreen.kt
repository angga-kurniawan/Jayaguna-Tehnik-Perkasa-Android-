package com.jayaguna.admin.features.partners.view

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.data.model.Partner
import com.jayaguna.admin.features.partners.controller.PartnerBatchState
import com.jayaguna.admin.features.partners.controller.PartnerLogoSelection
import com.jayaguna.admin.features.partners.controller.PendingPartnerDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Each selected image remains an independent partner, including its own editable details. */
@Composable
fun PartnerBatchScreen(
    state: PartnerBatchState,
    onSelect: (List<PartnerLogoSelection>) -> Unit,
    onUpdate: (Partner) -> Unit,
    onRemove: (String) -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    var readingSelection by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var leaveAfterDiscard by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val invalidOrders = remember { mutableStateMapOf<String, Boolean>() }
    val working = state.saving || readingSelection

    fun leave() {
        if (working) return
        if (state.selected.isEmpty()) onCancel()
        else { leaveAfterDiscard = true; confirmDiscard = true }
    }
    BackHandler { leave() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        if (uris.size > 8) {
            // Some system-picker fallbacks do not honor their requested selection limit.
            // Let the controller reject the oversized choice without querying every URI.
            onSelect(uris.take(9).map { PartnerLogoSelection(it.toString(), "") })
        } else if (uris.isNotEmpty()) {
            readingSelection = true
            scope.launch {
                try {
                    val selected = withContext(Dispatchers.IO) {
                        uris.map { uri ->
                            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            PartnerLogoSelection(uri.toString(), selectedLogoName(resolver, uri))
                        }
                    }
                    localError = null
                    onSelect(selected)
                } finally { readingSelection = false }
            }
        }
    }
    LaunchedEffect(state.selected.map { it.partner.id }) {
        invalidOrders.keys.toList().filter { id -> state.selected.none { it.partner.id == id } }
            .forEach(invalidOrders::remove)
    }
    val finishing = state.saving && state.progress != null && state.progress >= 1f
    LoadingFrame(state.saving,
        if (finishing) "Menyelesaikan penyimpanan…" else state.operation.ifBlank { "Menyimpan mitra…" },
        "Tunggu hingga logo dan data mitra dikonfirmasi.",
        progress = state.progress.takeIf { state.saving && !finishing }) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
            state = grid, modifier = Modifier.fillMaxSize().imePadding().testTag("partner-batch-grid"),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageHeading("Tambah mitra", "Setiap logo disimpan sebagai mitra tersendiri.")
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !working && state.selected.size < 8, modifier = Modifier.testTag("partner-batch-pick")) {
                        Text(if (readingSelection) "Menyiapkan logo…" else "+ Pilih logo")
                    }
                    Text("Pilih hingga 8 logo sekaligus. PNG, JPEG, atau WebP · maksimal 5 MiB per logo.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (localError != null || state.error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorNotice(localError ?: state.error)
            }
            if (state.notice != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(state.notice, color = MaterialTheme.colorScheme.primary)
            }
            if (state.selected.any { it.locked }) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Sebagian logo sudah dicoba disimpan. Datanya dikunci agar percobaan ulang memakai logo dan informasi yang sama. Logo yang sudah berhasil tidak diunggah ulang.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.selected.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyNotice("Belum ada logo dipilih", "Pilih logo, periksa nama setiap perusahaan, lalu simpan semuanya sekaligus.")
            }
            items(state.selected, key = { it.partner.id }) { pending ->
                PartnerLogoDraftCard(pending, working, onUpdate, onRemove) { invalid ->
                    invalidOrders[pending.partner.id] = invalid
                    if (!invalid) localError = null
                }
            }
            if (state.selected.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (state.selected.any { invalidOrders[it.partner.id] == true }) {
                            localError = "Periksa urutan tampil. Gunakan bilangan bulat 0–1.000.000."
                        } else { localError = null; onSave() }
                    }, enabled = !working, modifier = Modifier.fillMaxWidth().testTag("partner-batch-save")) {
                        Text(if (state.canRetry) "Coba simpan ${state.selected.size} mitra lagi" else "Simpan ${state.selected.size} mitra")
                    }
                    TextButton(onClick = { leaveAfterDiscard = false; confirmDiscard = true }, enabled = !working,
                        modifier = Modifier.fillMaxWidth().testTag("partner-batch-discard")) { Text("Batal memilih") }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = ::leave, enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag("partner-batch-back")) { Text("Kembali") }
            }
        }
    }
    if (confirmDiscard && !working) {
        AlertDialog(onDismissRequest = { confirmDiscard = false }, modifier = Modifier.testTag("partner-batch-discard-dialog"),
            title = { Text(if (leaveAfterDiscard) "Tinggalkan pilihan logo?" else "Batalkan pilihan logo?") },
            text = { Text(if (state.selected.any { it.locked })
                "Pilihan yang tersisa akan dibuang. Logo yang sudah berhasil disimpan tetap ada. Ada hasil penyimpanan yang belum dikonfirmasi; periksa daftar mitra sebelum menambah logo yang sama lagi."
            else "Pilihan logo yang belum disimpan akan dibuang. Mitra yang sudah berhasil disimpan tetap ada.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    invalidOrders.clear(); localError = null
                    onDiscard()
                    if (leaveAfterDiscard) onCancel()
                }, modifier = Modifier.testTag("partner-batch-confirm-discard")) { Text("Buang pilihan") }
            }, dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.testTag("partner-batch-keep")) { Text("Lanjutkan mengisi") }
            })
    }
}

@Composable
private fun PartnerLogoDraftCard(
    pending: PendingPartnerDraft, working: Boolean, onUpdate: (Partner) -> Unit,
    onRemove: (String) -> Unit, onInvalidOrder: (Boolean) -> Unit,
) {
    val partner = pending.partner
    val editable = !working && !pending.locked
    var details by rememberSaveable(partner.id) { mutableStateOf(false) }
    var order by rememberSaveable(partner.id) { mutableStateOf(partner.displayOrder.toString()) }
    val validOrder = order.toIntOrNull()?.takeIf { it in 0..1_000_000 }
    LaunchedEffect(order) { onInvalidOrder(validOrder == null) }
    OutlinedCard(Modifier.fillMaxWidth().testTag("partner-batch-card:${partner.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StorageImage(partner.logo, partner.altText.ifBlank { "Preview logo perusahaan" },
                Modifier.fillMaxWidth().height(160.dp), fit = true)
            Field(partner.name, { name ->
                val boundedName = name.take(160)
                val adjustedAlt = if (partner.altText == "Logo ${partner.name}") "Logo $boundedName" else partner.altText
                onUpdate(partner.copy(name = boundedName, altText = adjustedAlt.take(250)))
            }, "Nama perusahaan *", editable, modifier = Modifier.testTag("partner-batch-name:${partner.id}"))
            TextButton(onClick = { details = !details }, enabled = !working,
                modifier = Modifier.testTag("partner-batch-details:${partner.id}")) {
                Text(if (details) "Tutup detail" else "Detail mitra")
            }
            if (details) {
                Field(partner.altText, { onUpdate(partner.copy(altText = it.take(250))) }, "Teks alternatif logo *", editable,
                    modifier = Modifier.testTag("partner-batch-alt:${partner.id}"))
                Field(partner.website, { onUpdate(partner.copy(website = it.take(2048))) }, "Website (https://, opsional)", editable)
                Field(order, { value ->
                    order = value.take(7)
                    val parsed = order.toIntOrNull()?.takeIf { it in 0..1_000_000 }
                    onInvalidOrder(parsed == null)
                    // Keep an invalid sentinel in the draft too, so an offscreen
                    // restored field cannot silently save its previous valid order.
                    onUpdate(partner.copy(displayOrder = parsed ?: -1))
                }, "Urutan tampil", editable,
                    error = if (validOrder == null) "Gunakan bilangan bulat 0–1.000.000." else null,
                    modifier = Modifier.testTag("partner-batch-order:${partner.id}"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Tampilkan di website", Modifier.weight(1f))
                    Switch(partner.active, { onUpdate(partner.copy(active = it)) }, enabled = editable)
                }
            }
            if (pending.locked) Text("Menunggu hasil penyimpanan. Coba simpan lagi untuk melanjutkan.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onRemove(partner.id) }, enabled = editable,
                modifier = Modifier.testTag("partner-batch-remove:${partner.id}")) { Text("Hapus pilihan") }
        }
    }
}

private fun selectedLogoName(resolver: ContentResolver, uri: Uri): String = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column).orEmpty() else ""
    }.orEmpty()
}.getOrDefault("")
