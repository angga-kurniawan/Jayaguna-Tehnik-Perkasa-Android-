package com.jayaguna.admin.features.partners.view

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Partner
import java.util.UUID
import android.content.Intent

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartnersScreen(items: List<Partner>, busy: Boolean, error: String?, hasMore: Boolean,
                   onLoadMore: () -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit,
                   onEdit: (Partner) -> Unit, onArchive: (Partner) -> Unit, initialReady: Boolean = true,
                   loading: Boolean = busy) {
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val placeholderState = rememberLazyGridState()
    val fetching = loading || (!initialReady && items.isEmpty() && error == null)
    // Keep the real grid's restored anchor while only placeholder cards are shown.
    val contentState = if ((fetching || !initialReady) && items.isEmpty()) placeholderState else gridState
    RefreshFrame(fetching && items.isNotEmpty()) {
    // Keep text and actions readable when the device uses larger system fonts.
    val minimumCardWidth = 150.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    LazyVerticalGrid(columns = GridCells.Adaptive(minimumCardWidth), state = contentState,
        modifier = Modifier.fillMaxSize().testTag("partners-list"), contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { PageHeading("Perusahaan mitra", "Logo perusahaan yang pernah bekerja sama") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd, enabled = !busy) { Text("+ Tambah mitra") }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Perbarui") }
            }
        }
        if (fetching && items.isEmpty()) item(key = "partners-loading-status", span = { GridItemSpan(maxLineSpan) }) {
            LoadingStatus("Memuat perusahaan mitra…", "Menyiapkan logo dan informasi mitra.")
        }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorNotice(error, onRefresh) }
        if (items.isEmpty() && !fetching && !busy && error == null) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyNotice("Belum ada mitra", "Tambahkan beberapa logo sekaligus. Setiap logo disimpan sebagai mitra tersendiri.")
        }
        if (fetching && items.isEmpty()) items(8, key = { "partner-placeholder-$it" }) {
            CatalogLoadingPlaceholder(Modifier.fillMaxWidth().testTag("partners-loading-placeholder"), fitLogo = true)
        }
        items(items, key = { it.id }) { partner ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("partner-card:${partner.id}")) {
                Column {
                    Box(Modifier.fillMaxWidth().height(160.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp)) {
                        StorageImage(partner.logo, partner.altText.ifBlank { partner.name }, Modifier.fillMaxSize(), fit = true)
                    }
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(partner.name, style = MaterialTheme.typography.titleMedium,
                            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Urutan ${partner.displayOrder} · ${if (partner.active) "Aktif" else "Tersembunyi"}",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { onEdit(partner) }, enabled = !busy,
                            modifier = Modifier.testTag("partner-edit:${partner.id}")) { Text("Edit") }
                        TextButton(onClick = { archiveId = partner.id }, enabled = !busy,
                            modifier = Modifier.testTag("partner-archive:${partner.id}")) { Text("Arsipkan") }
                    }
                }
            }
        }
        if (hasMore && !fetching && (initialReady || items.isNotEmpty())) item(span = { GridItemSpan(maxLineSpan) }) { OutlinedButton(onClick = onLoadMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Muat lebih banyak") } }
    }
    }
    items.firstOrNull { it.id == archiveId }?.let { selected -> ConfirmAction("Arsipkan mitra?", "Logo ${selected.name} akan disembunyikan dari website. Riwayat tetap disimpan.",
        onConfirm = { archiveId = null; onArchive(selected) }, onDismiss = { archiveId = null }) }
}

@Composable
fun PartnerEditor(partner: Partner?, busy: Boolean, progress: Float?, error: String?,
                  onSave: (Partner) -> Unit, onCancel: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val initial = rememberSaveable(partner?.id, saver = PartnerStateSaver) { partner ?: Partner(id = UUID.randomUUID().toString()) }
    var draft by rememberSaveable(partner?.id, stateSaver = PartnerStateSaver) { mutableStateOf(initial) }
    var order by rememberSaveable(partner?.id) { mutableStateOf(initial.displayOrder.toString()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val dirty = draft != initial || order != initial.displayOrder.toString()
    val leave = { if (!busy) { if (dirty) confirmLeave = true else onCancel() } }
    BackHandler { leave() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            draft = draft.copy(logo = Media(storagePath = uri.toString()))
            localError = null
        }
    }
    val uploading = progress != null && progress < 1f
    LoadingFrame(busy, if (uploading) "Mengunggah logo…" else "Menyimpan mitra…",
        "Tunggu hingga perubahan dikonfirmasi.", progress = progress.takeIf { uploading }) {
    Column(Modifier.fillMaxSize().testTag("partner-editor-list").imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading(if (partner == null) "Tambah mitra" else "Edit mitra")
        ErrorNotice(localError ?: error)
        Field(draft.name, { draft = draft.copy(name = it) }, "Nama perusahaan *", !busy, modifier = Modifier.testTag("partner-name"))
        Text("Logo perusahaan", style = MaterialTheme.typography.titleMedium)
        StorageImage(draft.logo, draft.altText.ifBlank { "Preview logo perusahaan" }, Modifier.fillMaxWidth().height(180.dp), fit = true)
        Text("PNG transparan, JPEG, atau WebP · maksimal 5 MiB", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
            Text(if (draft.logo == null) "Pilih logo" else "Ganti logo")
        }
        Field(draft.altText, { draft = draft.copy(altText = it) }, "Teks alternatif logo *", !busy)
        Field(draft.website, { draft = draft.copy(website = it) }, "Website perusahaan (https://, opsional)", !busy)
        Field(order, { order = it }, "Urutan tampil (0 atau lebih besar)", !busy)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("Tampilkan di website"); Text("Logo mitra aktif terlihat oleh pengunjung.", style = MaterialTheme.typography.bodySmall) }
            Switch(checked = draft.active, onCheckedChange = { draft = draft.copy(active = it) }, enabled = !busy)
        }
        Button(onClick = {
            localError = when {
                draft.name.isBlank() || draft.altText.isBlank() -> "Nama perusahaan dan teks alternatif wajib diisi."
                order.toIntOrNull() == null || (order.toIntOrNull() ?: -1) < 0 -> "Urutan tampil harus berupa bilangan bulat 0 atau lebih besar."
                draft.website.isNotBlank() && !draft.website.startsWith("https://") -> "Website perusahaan harus menggunakan https://."
                draft.active && draft.logo == null -> "Pilih logo sebelum mengaktifkan mitra."
                else -> null
            }
            if (localError == null) onSave(draft.copy(name = draft.name.trim(), altText = draft.altText.trim(), website = draft.website.trim(), displayOrder = order.toInt()))
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Menyimpan…" else "Simpan mitra") }
        TextButton(onClick = leave, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
    }
    }
    if (confirmLeave) ConfirmAction("Tinggalkan formulir?", "Perubahan yang belum disimpan akan hilang.",
        onConfirm = { confirmLeave = false; onCancel() }, onDismiss = { confirmLeave = false })
}
