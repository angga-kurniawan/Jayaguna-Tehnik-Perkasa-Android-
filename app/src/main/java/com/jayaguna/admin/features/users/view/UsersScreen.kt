package com.jayaguna.admin.features.users.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.data.model.AdminUser

@Composable
fun UsersScreen(items: List<AdminUser>, busy: Boolean, error: String?, onRefresh: () -> Unit,
                hasMore: Boolean = false, onLoadMore: () -> Unit = {}, initialReady: Boolean = true,
                loading: Boolean = busy, ownerUid: String = "", onRemoveAdmin: ((AdminUser) -> Unit)? = null) {
    val listState = rememberLazyListState()
    val placeholderState = rememberLazyListState()
    val fetching = loading || (!initialReady && items.isEmpty() && error == null)
    var removalUid by rememberSaveable { mutableStateOf<String?>(null) }
    fun removable(user: AdminUser): Boolean = onRemoveAdmin != null && ownerUid.isNotBlank() &&
        user.uid.isNotBlank() && user.uid != ownerUid && user.role == "ADMIN" && user.status != "revoked"
    val selected = items.firstOrNull { it.uid == removalUid && removable(it) }
    LaunchedEffect(removalUid, selected) {
        if (removalUid != null && selected == null) removalUid = null
    }
    RefreshFrame(fetching && items.isNotEmpty()) {
        LazyColumn(Modifier.fillMaxSize().testTag("users-list"), state = if ((fetching || !initialReady) && items.isEmpty()) placeholderState else listState,
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PageHeading("Akses pengguna", "Daftar akun perusahaan · khusus OWNER") }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Persetujuan oleh pemilik", style = MaterialTheme.typography.titleMedium)
                        Text("Pengguna masuk dengan akun Google masing-masing. Pemilik meninjau permohonan dan mengatur persetujuan, peran, serta status akses melalui Firebase Console.", style = MaterialTheme.typography.bodyMedium)
                        Text("Pemilik dapat menghapus akses admin yang sudah tidak bekerja melalui daftar ini. Permohonan akses baru tetap menunggu persetujuan melalui Firebase Console.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { TextButton(onClick = onRefresh, enabled = !busy && !fetching) { Text("Perbarui") } }
            if (error != null) item { ErrorNotice(error, onRefresh) }
            if (fetching && items.isEmpty()) item {
                LoadingStatus("Memuat daftar pengguna…", "Memeriksa informasi akun dan akses.")
            }
            if (fetching && items.isEmpty()) {
                items(4, key = { "users-loading-$it" }) {
                    RecordLoadingPlaceholder(Modifier.testTag("users-loading-placeholder"), lines = 4)
                }
            }
            if (items.isEmpty() && !fetching && !busy && error == null) item {
                EmptyNotice("Belum ada pengguna", "Akun yang disetujui dan dicatat pengelola akan tampil di sini.")
            }
            items(items, key = { it.uid }) { user ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(user.name, style = MaterialTheme.typography.titleMedium)
                        Text(user.email)
                        val status = when { user.status == "revoked" -> "Akses dihapus"; user.status == "pending" -> "Menunggu persetujuan"; user.status == "rejected" -> "Ditolak"; user.active -> "Aktif"; else -> "Nonaktif" }
                        Text("${user.role} · $status", style = MaterialTheme.typography.labelLarge)
                        if (removable(user)) {
                            TextButton(onClick = { removalUid = user.uid }, enabled = !busy && !fetching,
                                modifier = Modifier.testTag("remove-admin:${user.uid}")) { Text("Hapus admin") }
                        }
                    }
                }
            }
            if (hasMore && !fetching && (initialReady || items.isNotEmpty())) item {
                OutlinedButton(onClick = onLoadMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Muat lebih banyak") }
            }
        }
    }
    if (selected != null) {
        AlertDialog(
            modifier = Modifier.testTag("remove-admin-dialog"),
            onDismissRequest = { removalUid = null },
            title = { Text("Hapus akses admin?") },
            text = { Text("Akses aplikasi untuk ${selected.name.ifBlank { selected.email }} (${selected.email}) akan dicabut. Riwayat perubahan tetap tersimpan. Akses folder Google Drive perlu dicabut melalui Google Drive.") },
            confirmButton = {
                TextButton(onClick = {
                    // Resolve the current item again: a refresh may have changed its role or removed it.
                    val target = items.firstOrNull { it.uid == removalUid && removable(it) }
                    removalUid = null
                    if (!busy && !fetching && target != null) onRemoveAdmin?.invoke(target)
                }, enabled = !busy && !fetching, modifier = Modifier.testTag("confirm-remove-admin")) { Text("Hapus admin") }
            },
            dismissButton = {
                TextButton(onClick = { removalUid = null }, modifier = Modifier.testTag("cancel-remove-admin")) { Text("Batal") }
            }
        )
    }
}
