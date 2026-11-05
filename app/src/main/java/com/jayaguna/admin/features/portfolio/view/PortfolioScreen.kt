package com.jayaguna.admin.features.portfolio.view

import android.content.Intent
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.PortfolioImage
import com.jayaguna.admin.features.portfolio.controller.PortfolioState

@Composable
fun PortfolioScreen(state: PortfolioState, onCategory: (String) -> Unit, onSelect: (List<String>) -> Unit,
                    onRemoveSelection: (String) -> Unit, onDiscard: () -> Unit, onUpload: () -> Unit,
                    onReplace: (PortfolioImage, String) -> Unit, onDelete: (PortfolioImage) -> Unit,
                    onRetry: () -> Unit, onRefresh: () -> Unit, onLoadMore: () -> Unit, initialReady: Boolean = true) {
    val resolver = LocalContext.current.contentResolver
    var replacingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        uris.forEach { uri -> runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        onSelect(uris.map { it.toString() })
    }
    val replacement = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = state.items.firstOrNull { it.id == replacingId }
        replacingId = null
        if (uri != null && target != null) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onReplace(target, uri.toString())
        }
    }
    val canEdit = !state.saving && state.selected.isEmpty()
    val fetching = state.loading || (!initialReady && state.items.isEmpty() && state.listError == null)
    val categoryStates = rememberSaveableStateHolder()
    val finishing = state.saving && state.progress != null && state.progress >= 1f
    val feedback = when {
        finishing -> "Menyelesaikan penyimpanan…"
        state.saving -> state.operation.ifBlank { "Menyimpan portofolio…" }
        else -> "Memuat gambar portofolio…"
    }
    categoryStates.SaveableStateProvider(state.category) {
    val gridState = rememberLazyGridState()
    val placeholderState = rememberLazyGridState()
    LoadingFrame(state.saving, feedback, "Tunggu hingga perubahan dikonfirmasi.",
        progress = state.progress.takeIf { state.saving && !finishing }) {
    RefreshFrame(fetching && state.items.isNotEmpty()) {
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), state = if ((fetching || !initialReady) && state.items.isEmpty()) placeholderState else gridState, modifier = Modifier.fillMaxSize().testTag("portfolio-grid"), contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { PageHeading("Portofolio", "Kelola gambar pekerjaan perumahan dan perusahaan.") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChoiceRow("Kategori", state.category, listOf("residential" to "Perumahan", "corporate" to "Perusahaan"), onCategory, canEdit)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.saving && state.selected.size < 8) { Text("+ Pilih gambar") }
                TextButton(onClick = onRefresh, enabled = !state.saving && !fetching) { Text("Perbarui") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Text("PNG, JPEG, atau WebP · maksimal 5 MiB per gambar", style = MaterialTheme.typography.bodySmall) }
        if (state.error != null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorNotice(state.error, if (state.canRetry) onRetry else null) }
        if (state.notice != null) item(span = { GridItemSpan(maxLineSpan) }) { Text(state.notice, color = MaterialTheme.colorScheme.primary) }
        if (state.selected.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Preview pilihan · ${state.selected.size} gambar", style = MaterialTheme.typography.titleMedium) }
            items(state.selected, key = { "selected-${it.id}" }) { selected ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column {
                        StorageImage(Media(storagePath = selected.uri), "Preview gambar pilihan", Modifier.fillMaxWidth().height(150.dp))
                        TextButton(onClick = { onRemoveSelection(selected.id) }, enabled = !state.saving) { Text("Hapus pilihan") }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onUpload, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("Simpan ${state.selected.size} gambar") }
                    TextButton(onClick = onDiscard, enabled = !state.saving) { Text("Batal memilih") }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Text(if (state.category == "residential") "Gambar perumahan" else "Gambar perusahaan", style = MaterialTheme.typography.titleMedium) }
        if (state.listError != null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorNotice(state.listError, onRefresh) }
        if (fetching && state.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            LoadingStatus("Memuat gambar portofolio…", "Menyiapkan gambar kategori pilihan.")
        }
        if (fetching && state.items.isEmpty()) items(8, key = { "loading-$it" }) {
            CatalogLoadingPlaceholder(Modifier.fillMaxWidth(), showDetails = false)
        }
        if (!fetching && state.items.isEmpty() && state.listError == null) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyNotice("Belum ada gambar", "Pilih gambar untuk menambahkan portofolio kategori ini.")
        }
        items(state.items, key = { it.id }) { image ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column {
                    StorageImage(image.image, if (image.category == "residential") "Portofolio perumahan" else "Portofolio perusahaan", Modifier.fillMaxWidth().height(160.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { replacingId = image.id; replacement.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = canEdit) { Text("Ganti") }
                        TextButton(onClick = { deletingId = image.id }, enabled = canEdit) { Text("Hapus") }
                    }
                }
            }
        }
        if (state.hasMore && !fetching && (initialReady || state.items.isNotEmpty())) item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedButton(onClick = onLoadMore, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("Muat lebih banyak") }
        }
    }
    }
    }
    }
    state.items.firstOrNull { it.id == deletingId }?.let { image -> ConfirmAction("Hapus gambar portofolio?", "Gambar akan dihapus dari portofolio. File Drive dibersihkan setelah perubahan berhasil disimpan.",
        onConfirm = { deletingId = null; onDelete(image) }, onDismiss = { deletingId = null }) }
}
