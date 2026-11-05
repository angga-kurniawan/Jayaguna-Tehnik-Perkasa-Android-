package com.jayaguna.admin.features.activity.view

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.data.model.AuditEntry

@Composable
fun ActivityScreen(items: List<AuditEntry>, busy: Boolean, error: String?, hasMore: Boolean,
                   onLoadMore: () -> Unit, onRefresh: () -> Unit,
                   onFilter: (String, String, String, String, String) -> Unit,
                   owner: Boolean = false, initialReady: Boolean = true) {
    var startDate by rememberSaveable { mutableStateOf("") }
    var endDate by rememberSaveable { mutableStateOf("") }
    var actor by rememberSaveable { mutableStateOf("") }
    var entity by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val placeholderState = rememberLazyListState()
    val fetching = busy || (!initialReady && items.isEmpty() && error == null)
    RefreshFrame(fetching && items.isNotEmpty()) {
    LazyColumn(Modifier.fillMaxSize().testTag("activity-list"), state = if ((fetching || !initialReady) && items.isEmpty()) placeholderState else listState,
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("Riwayat aktivitas", if (owner) "Perubahan yang dikonfirmasi server" else "Riwayat perubahan akun Anda") }
        item { Row {
            TextButton(onClick = { filters = !filters }) { Text(if (filters) "Tutup filter" else "Filter riwayat") }
            TextButton(onClick = onRefresh, enabled = !fetching) { Text("Perbarui") }
        } }
        if (filters) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(startDate, { startDate = it }, "Dari tanggal (YYYY-MM-DD)")
                Field(endDate, { endDate = it }, "Sampai tanggal (YYYY-MM-DD)")
                if (owner) Field(actor, { actor = it }, "UID pengguna (opsional)")
                ChoiceRow("Jenis data", entity, listOf("" to "Semua", "product" to "Produk", "partner" to "Mitra", "portfolioResidential" to "Portofolio perumahan", "portfolioCorporate" to "Portofolio perusahaan", "user" to "Admin"), { entity = it })
                ChoiceRow("Aksi", action, listOf("" to "Semua", "add" to "Tambah", "edit" to "Edit", "archive" to "Arsip", "status" to "Ubah status", "removeAdmin" to "Hapus admin"), { action = it })
                FilledTonalButton(onClick = { onFilter(startDate.trim(), endDate.trim(), if (owner) actor.trim() else "", entity, action) }, enabled = !fetching) { Text("Terapkan filter") }
            }
        }
        if (error != null) item { ErrorNotice(error, onRefresh) }
        if (fetching && items.isEmpty()) item {
            LoadingStatus("Memuat riwayat aktivitas…", "Menyiapkan perubahan sesuai filter.")
        }
        if (fetching && items.isEmpty()) {
            items(4, key = { "activity-loading-$it" }) {
                RecordLoadingPlaceholder(Modifier.testTag("activity-loading-placeholder"), lines = 5)
            }
        }
        if (items.isEmpty() && !fetching && error == null) item { EmptyNotice("Belum ada aktivitas", "Riwayat yang sesuai filter akan ditampilkan di sini.") }
        items(items, key = { it.id }) { entry ->
            OutlinedCard(onClick = { detailId = entry.id }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${entry.actorName} · ${entry.actorRole}", fontWeight = FontWeight.Medium)
                    Text(if (entry.action == "removeAdmin") "Hapus admin · Pengguna" else "${entry.action} · ${entry.entityType}", color = MaterialTheme.colorScheme.primary)
                    Text(entry.timestamp, style = MaterialTheme.typography.bodySmall)
                    Text(entry.device.deviceName.ifBlank { "Perangkat tanpa nama" }, style = MaterialTheme.typography.bodySmall)
                    Text("Lihat detail perubahan →", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (hasMore && !fetching && (initialReady || items.isNotEmpty())) item {
            OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("Muat lebih banyak") }
        }
    }
    }
    items.firstOrNull { it.id == detailId }?.let { entry ->
        AlertDialog(onDismissRequest = { detailId = null }, title = { Text("Detail perubahan") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${entry.actorName} (${entry.actorRole})", fontWeight = FontWeight.Bold)
                    Text("UID: ${entry.actorUid}")
                    Text("${entry.action} · ${entry.entityType}\nID: ${entry.entityId}")
                    Text("Disimpan: ${entry.timestamp}")
                    HorizontalDivider()
                    entry.changedFields.forEach { (name, change) ->
                        Text(name, fontWeight = FontWeight.Bold)
                        Text("Sebelum: ${change.before.ifBlank { "—" }}")
                        Text("Sesudah: ${change.after.ifBlank { "—" }}")
                    }
                    HorizontalDivider()
                    Text("${entry.device.deviceName}\n${entry.device.brand} ${entry.device.model}\nAndroid ${entry.device.androidVersion} · aplikasi ${entry.device.appVersion}")
                    Text("Instalasi: ${entry.device.installationId}\nRequest: ${entry.requestId}", style = MaterialTheme.typography.bodySmall)
                    Text("Nama dan model perangkat merupakan metadata yang dilaporkan aplikasi.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { detailId = null }) { Text("Tutup") } })
    }
}
