package com.jayaguna.admin.features.dashboard.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.data.model.Dashboard
import com.jayaguna.admin.core.theme.JayagunaBrand

@Composable
fun DashboardScreen(data: Dashboard?, busy: Boolean, error: String?, onRetry: () -> Unit,
                    onNavigate: (String) -> Unit) {
    val scrollState = rememberScrollState()
    val placeholderScrollState = rememberScrollState()
    val fetching = busy || (data == null && error == null)
    RefreshFrame(fetching && data != null) {
    Column(Modifier.fillMaxSize().testTag("dashboard-scroll").verticalScroll(if (data == null) placeholderScrollState else scrollState).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = JayagunaBrand.Navy, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandEyebrow("PORTAL INTERNAL", color = Color(0xFFE2B934))
                Text("Ringkasan bisnis.", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("Pantau produk AC dan perusahaan mitra Anda.", color = Color(0xFFCCD6EB), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (fetching && data == null) LoadingStatus("Memuat ringkasan bisnis…", "Menghitung produk dan mitra terbaru.")
        ErrorNotice(error, onRetry)
        if (data != null) {
            StatCard("Produk aktif", data.activeProducts, "Ditampilkan di website", { onNavigate("products") })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { StatCard("Draft / nonaktif", data.draftProducts, "Produk tersimpan", { onNavigate("products") }) }
                Box(Modifier.weight(1f)) { StatCard("Mitra aktif", data.activePartners, "Logo ditampilkan", { onNavigate("partners") }) }
            }
        } else if (fetching) {
            RecordLoadingPlaceholder(lines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { RecordLoadingPlaceholder(lines = 3) }
                Box(Modifier.weight(1f)) { RecordLoadingPlaceholder(lines = 3) }
            }
        }
        Text("Akses cepat", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onNavigate("addProduct") }, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) { Text("+ Produk") }
            FilledTonalButton(onClick = { onNavigate("addPartner") }, shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) { Text("+ Mitra") }
        }
        OutlinedButton(onClick = { onNavigate("portfolio") }, modifier = Modifier.fillMaxWidth()) { Text("Kelola gambar portofolio") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Aktivitas terbaru", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onNavigate("activity") }) { Text("Lihat semua") }
        }
        if (fetching && data?.recent.isNullOrEmpty()) repeat(2) { RecordLoadingPlaceholder(lines = 2) }
        if (data != null && data.recent.isEmpty() && !fetching) EmptyNotice("Belum ada aktivitas", "Perubahan yang berhasil disimpan akan muncul di sini.")
        data?.recent?.forEach { item ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${item.actorName} · ${item.action}", fontWeight = FontWeight.Medium)
                    Text("${item.entityType} · ${item.timestamp}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Perbarui ringkasan") }
    }
    }
}

@Composable
private fun StatCard(title: String, value: Int, detail: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, JayagunaBrand.Line),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(22.dp).height(3.dp).background(JayagunaBrand.Red))
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value.toString(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                color = JayagunaBrand.Navy)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
