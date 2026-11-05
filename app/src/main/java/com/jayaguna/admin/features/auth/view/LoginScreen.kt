package com.jayaguna.admin.features.auth.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.BuildConfig
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.core.theme.JayagunaBrand
import com.jayaguna.admin.data.model.AccessIdentity
import java.util.Locale

@Composable
fun LoginScreen(
    busy: Boolean,
    error: String?,
    notice: String?,
    onGoogleSignIn: () -> Unit,
    identity: AccessIdentity? = null,
    requestSubmitting: Boolean = false,
    onRequestAccess: () -> Unit = {},
    canRetrySession: Boolean = false,
    onRetrySession: () -> Unit = {},
    onChangeAccount: () -> Unit = {}
) {
    val working = busy || requestSubmitting
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFF7F9FC), Color(0xFFEEF3F9))))
            .safeDrawingPadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BrandIdentity()
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                    Text(
                        if (BuildConfig.DEBUG) "DEBUG" else "INTERNAL",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        HorizontalDivider(color = JayagunaBrand.Line)
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(6.dp))
            BrandEyebrow("PT JAYAGUNA TEHNIK PERKASA")
            Text(buildAnnotatedString {
                append("Kelola katalog\nperusahaan")
                withStyle(SpanStyle(color = JayagunaBrand.Red)) { append(".") }
            }, style = MaterialTheme.typography.headlineLarge, color = JayagunaBrand.Navy)
            Text("Satu tempat untuk mengelola produk AC dan perusahaan mitra.", color = JayagunaBrand.Muted)
            Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, JayagunaBrand.Line), color = Color.White) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (identity == null) "Masuk dengan Google" else "Akses aplikasi perusahaan", style = MaterialTheme.typography.titleLarge)
                        Text("Akses khusus pengguna yang disetujui pemilik.", style = MaterialTheme.typography.bodySmall, color = JayagunaBrand.Muted)
                    }
                    ErrorNotice(error)
                    if (notice != null) Text(notice, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                    if (identity != null) {
                        Column(Modifier.testTag("login-access-identity"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(identity.name.ifBlank { identity.email }, style = MaterialTheme.typography.titleMedium)
                            Text(identity.email, style = MaterialTheme.typography.bodyMedium)
                        }
                        val status = identity.status.lowercase(Locale.ROOT)
                        val reviewed = status in setOf("rejected", "denied", "revoked", "inactive", "disabled")
                        Text(
                            when {
                                status in setOf("rejected", "denied") -> "Permohonan akses belum disetujui pemilik. Hubungi pemilik jika perlu mengajukan kembali."
                                status in setOf("revoked", "inactive", "disabled") -> "Akses akun ini telah dinonaktifkan. Hubungi pemilik perusahaan."
                                identity.requestPending -> "Permohonan Anda sudah tersimpan dan menunggu persetujuan pengelola."
                                else -> "Akun Google Anda sudah masuk. Ajukan akses untuk menunggu persetujuan pemilik perusahaan."
                            }, style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = onRequestAccess,
                            enabled = !working && !identity.requestPending && !reviewed,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("login-request-access")
                        ) {
                            if (requestSubmitting) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Text(
                                when {
                                    requestSubmitting -> "Mengirim permohonan…"
                                    identity.requestPending -> "Permohonan sudah diajukan"
                                    reviewed -> "Hubungi pemilik untuk akses"
                                    else -> "Ajukan Permohonan Akses"
                                }, Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    } else if (!canRetrySession) {
                        Text("Gunakan akun Google Anda untuk masuk. Pemilik tetap menentukan siapa yang dapat mengakses aplikasi.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = onGoogleSignIn, enabled = !working,
                            border = BorderStroke(1.dp, Color(0xFF747775)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1F1F1F), containerColor = Color.White),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("login-google")
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(if (busy) "Memeriksa akun…" else "Lanjutkan dengan Google", Modifier.padding(horizontal = 8.dp))
                        }
                    }
                    if (identity != null || canRetrySession) {
                        if (identity == null) Text("Sesi Google Anda tersimpan. Periksa kembali akses untuk melanjutkan.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(
                            onClick = onRetrySession, enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("login-refresh-access")
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(if (busy) "Memeriksa akses…" else "Periksa status akses", Modifier.padding(horizontal = 8.dp))
                        }
                        TextButton(
                            onClick = onChangeAccount, enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("login-change-account")
                        ) { Text("Gunakan akun lain") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).background(JayagunaBrand.Gold, MaterialTheme.shapes.extraSmall))
                Text("Pemasangan, perawatan & penjualan AC", style = MaterialTheme.typography.bodySmall, color = JayagunaBrand.Muted)
            }
        }
    }
}
