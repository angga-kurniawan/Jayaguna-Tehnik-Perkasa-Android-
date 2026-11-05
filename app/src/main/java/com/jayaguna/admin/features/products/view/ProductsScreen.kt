package com.jayaguna.admin.features.products.view

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Product
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import android.content.Intent

private val Categories = listOf("" to "Semua", "split" to "Split", "cassette" to "Cassette",
    "standing" to "Standing", "ducted" to "Ducted", "central" to "Central", "other" to "Lainnya")
private val Statuses = listOf("" to "Semua", "draft" to "Draft", "active" to "Aktif", "inactive" to "Nonaktif")

@Composable
fun ProductsScreen(items: List<Product>, busy: Boolean, error: String?, hasMore: Boolean,
                   onLoadMore: () -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit,
                   onEdit: (Product) -> Unit, onArchive: (Product) -> Unit,
                   onFilter: (String, String, String) -> Unit = { _, _, _ -> }, initialReady: Boolean = true,
                   loading: Boolean = busy) {
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("") }
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val placeholderState = rememberLazyListState()
    val fetching = loading || (!initialReady && items.isEmpty() && error == null)
    // Do not clamp the restored catalog anchor to the shorter placeholder list.
    val contentState = if ((fetching || !initialReady) && items.isEmpty()) placeholderState else listState
    RefreshFrame(fetching && items.isNotEmpty()) {
    LazyColumn(Modifier.fillMaxSize().testTag("products-list").scrollPositionSemantics(contentState), state = contentState,
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("Produk AC", "Kelola katalog yang terhubung ke website") }
        if (fetching && items.isEmpty()) item(key = "products-loading-status") {
            LoadingStatus("Memuat produk AC…", "Daftar sedang diperbarui.")
        }
        item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("+ Tambah produk") } }
        item { Field(search, { search = it }, "Awal nama atau merek", enabled = !busy, modifier = Modifier.testTag("product-search")) }
        item { ChoiceRow("Kategori", category, Categories, { category = it }, enabled = !busy) }
        item { ChoiceRow("Status", status, Statuses, { status = it }, enabled = !busy) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onFilter(search.trim(), category, status) }, enabled = !busy) { Text("Terapkan pencarian") }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Perbarui") }
            }
        }
        item { ErrorNotice(error, onRefresh) }
        if (items.isEmpty() && !fetching && !busy && error == null) item { EmptyNotice("Produk belum ditemukan", "Tambahkan produk atau sesuaikan filter pencarian.") }
        if (fetching && items.isEmpty()) items(3, key = { "product-placeholder-$it" }) {
            CatalogLoadingPlaceholder(Modifier.fillMaxWidth())
        }
        items(items, key = { it.id }) { product ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (product.images.isNotEmpty()) StorageImage(
                        product.images.find { it.storagePath == product.mainImagePath } ?: product.images.first(),
                        product.name, Modifier.fillMaxWidth().height(144.dp))
                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                    Text("${product.brand} · ${product.model}", style = MaterialTheme.typography.bodyMedium)
                    Text("${Categories.find { it.first == product.category }?.second ?: product.category} · ${Statuses.find { it.first == product.status }?.second ?: product.status}",
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    product.price?.let { Text(NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).format(it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onEdit(product) }, enabled = !busy) { Text("Edit produk") }
                        TextButton(onClick = { archiveId = product.id }, enabled = !busy) { Text("Arsipkan") }
                    }
                }
            }
        }
        if (hasMore && !fetching && (initialReady || items.isNotEmpty())) item { OutlinedButton(onClick = onLoadMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Muat lebih banyak") } }
    }
    }
    items.firstOrNull { it.id == archiveId }?.let { selected -> ConfirmAction("Arsipkan produk?", "${selected.name} akan disembunyikan dari website. Data dan riwayat tetap disimpan.",
        onConfirm = { archiveId = null; onArchive(selected) }, onDismiss = { archiveId = null }) }
}

@Composable
fun ProductEditor(product: Product?, busy: Boolean, progress: Float?, error: String?,
                  onSave: (Product) -> Unit, onCancel: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val initial = rememberSaveable(product?.id, saver = ProductStateSaver) { product ?: Product(id = UUID.randomUUID().toString()) }
    var draft by rememberSaveable(product?.id, stateSaver = ProductStateSaver) { mutableStateOf(initial) }
    var price by rememberSaveable(product?.id) { mutableStateOf(initial.price?.toString() ?: "") }
    var specs by rememberSaveable(product?.id) { mutableStateOf(initial.specifications.entries.joinToString("\n") { "${it.key}: ${it.value}" }) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var removeImage by remember { mutableStateOf<Media?>(null) }
    val dirty = draft != initial || price != (initial.price?.toString() ?: "") || specs != initial.specifications.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    val leave = { if (!busy) { if (dirty) confirmLeave = true else onCancel() } }
    BackHandler { leave() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        val merged = (draft.images + uris.map { Media(storagePath = it.toString()) }).distinctBy { it.storagePath }
        if (merged.size > 8) localError = "Maksimal 8 foto produk."
        else {
            uris.forEach { uri -> runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            draft = draft.copy(images = merged, mainImagePath = draft.mainImagePath.ifBlank { merged.firstOrNull()?.storagePath ?: "" })
            localError = null
        }
    }
    val uploading = progress != null && progress < 1f
    LoadingFrame(busy, if (uploading) "Mengunggah foto produk…" else "Menyimpan produk…",
        "Tunggu hingga perubahan dikonfirmasi.", progress = progress.takeIf { uploading }) {
    Column(Modifier.fillMaxSize().testTag("product-editor-list").imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeading(if (product == null) "Tambah produk" else "Edit produk", "Perubahan ditampilkan setelah dikonfirmasi server")
        ErrorNotice(localError ?: error)
        Field(draft.name, { draft = draft.copy(name = it) }, "Nama produk *", !busy, modifier = Modifier.testTag("product-name"))
        Field(draft.brand, { draft = draft.copy(brand = it) }, "Merek *", !busy)
        Field(draft.model, { draft = draft.copy(model = it) }, "Model / tipe", !busy)
        ChoiceRow("Kategori", draft.category, Categories.drop(1), { draft = draft.copy(category = it) }, !busy)
        Field(draft.capacityPk, { draft = draft.copy(capacityPk = it) }, "Kapasitas PK (opsional)", !busy)
        ChoiceRow("Kondisi", draft.condition, listOf("new" to "Baru", "used" to "Bekas"), { draft = draft.copy(condition = it) }, !busy)
        Field(price, { price = it }, "Harga rupiah (opsional, angka tanpa pemisah)", !busy)
        Field(draft.description, { draft = draft.copy(description = it) }, "Deskripsi", !busy, false)
        Field(specs, { specs = it }, "Spesifikasi · satu Nama: Nilai per baris", !busy, false)
        ChoiceRow("Status publikasi", draft.status, Statuses.drop(1), { draft = draft.copy(status = it) }, !busy)
        Text("Foto produk", style = MaterialTheme.typography.titleMedium)
        Text("PNG, JPEG, atau WebP · maksimal 5 MiB per foto · maksimal 8 foto", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy && draft.images.size < 8) { Text("+ Pilih foto") }
        draft.images.forEachIndexed { index, media ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    StorageImage(media, "Foto produk ${index + 1}", Modifier.fillMaxWidth().height(180.dp))
                    Row {
                        RadioButton(selected = draft.mainImagePath == media.storagePath,
                            onClick = { draft = draft.copy(mainImagePath = media.storagePath) }, enabled = !busy)
                        TextButton(onClick = { draft = draft.copy(mainImagePath = media.storagePath) }, enabled = !busy) { Text("Foto utama") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            val moved = draft.images.toMutableList(); moved.add(index - 1, moved.removeAt(index)); draft = draft.copy(images = moved)
                        }, enabled = !busy && index > 0) { Text("↑ Naik") }
                        TextButton(onClick = {
                            val moved = draft.images.toMutableList(); moved.add(index + 1, moved.removeAt(index)); draft = draft.copy(images = moved)
                        }, enabled = !busy && index < draft.images.lastIndex) { Text("↓ Turun") }
                        TextButton(onClick = {
                            removeImage=media
                        }, enabled = !busy) { Text("Hapus") }
                    }
                }
            }
        }
        Button(onClick = {
            localError = when {
                draft.name.isBlank() || draft.brand.isBlank() -> "Nama dan merek produk wajib diisi."
                price.isNotBlank() && (price.toDoubleOrNull() == null || price.toDouble() < 0 || !price.toDouble().isFinite()) -> "Harga harus berupa angka positif atau nol."
                specs.lineSequence().filter { it.isNotBlank() }.any { !it.contains(":") || it.substringBefore(":").isBlank() } -> "Tulis spesifikasi dengan format Nama: Nilai."
                draft.status == "active" && draft.images.isEmpty() -> "Tambahkan foto sebelum mengaktifkan produk."
                else -> null
            }
            if (localError == null) onSave(draft.copy(name = draft.name.trim(), brand = draft.brand.trim(), price = price.toDoubleOrNull(),
                specifications = specs.lineSequence().filter { it.isNotBlank() }.associate { it.substringBefore(":").trim() to it.substringAfter(":").trim() }))
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Menyimpan…" else "Simpan produk") }
        TextButton(onClick = leave, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
    }
    }
    if (confirmLeave) ConfirmAction("Tinggalkan formulir?", "Perubahan yang belum disimpan akan hilang.",
        onConfirm = { confirmLeave = false; onCancel() }, onDismiss = { confirmLeave = false })
    removeImage?.let { media -> ConfirmAction("Hapus foto dari produk?", "Perubahan berlaku setelah produk disimpan. Foto yang masih digunakan tidak akan dihapus dari Drive.",
        onConfirm={val next=draft.images.filterNot {it.storagePath==media.storagePath};draft=draft.copy(images=next,mainImagePath=if(draft.mainImagePath==media.storagePath) next.firstOrNull()?.storagePath ?: "" else draft.mainImagePath);removeImage=null},
        onDismiss={removeImage=null}) }
}
