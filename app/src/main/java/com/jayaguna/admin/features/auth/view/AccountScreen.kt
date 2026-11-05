package com.jayaguna.admin.features.auth.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jayaguna.admin.core.components.*
import com.jayaguna.admin.data.model.Account
import com.jayaguna.admin.data.model.DeviceInfo

@Composable
fun AccountScreen(account: Account, device: DeviceInfo, onRenameDevice: (String) -> Unit, onLogout: () -> Unit,
                  driveConnected:Boolean=false,driveEmail:String="",driveBusy:Boolean=false,driveError:String?=null,
                  onRefreshDrive:()->Unit={},initialReady:Boolean=true) {
    var name by rememberSaveable(device.deviceName) { mutableStateOf(device.deviceName) }
    var logout by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val initialScrollState = rememberScrollState()
    val checkingDrive = driveBusy || !initialReady
    Column(Modifier.fillMaxSize().testTag("account-scroll").verticalScroll(if (initialReady) scrollState else initialScrollState).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeading("Akun & perangkat", "Identitas untuk setiap perubahan")
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(account.name, style = MaterialTheme.typography.titleLarge)
                Text(account.email)
                Text(account.role, color = MaterialTheme.colorScheme.primary)
                Text("UID: ${account.uid}", style = MaterialTheme.typography.bodySmall)
            }
        }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Google Drive perusahaan",style=MaterialTheme.typography.titleMedium)
                    if (checkingDrive) {
                        LoadingStatus("Memeriksa Google Drive…", "Memeriksa sambungan penyimpanan perusahaan.")
                    } else {
                        Text(if (driveConnected) { if (driveEmail.isNotBlank()) "Terhubung dengan akun Google Anda: $driveEmail" else "Folder perusahaan terhubung." } else "Google Drive belum terhubung. Izinkan akun Google Anda mengakses folder perusahaan.")
                        ErrorNotice(driveError)
                    }
                    Text("Pemilik perlu membagikan keempat folder perusahaan ke ${account.email} sebagai Editor. File baru dan kuota penyimpanannya mengikuti akun pengunggah.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick=onRefreshDrive,enabled=!checkingDrive) {Text(if (driveConnected) "Periksa koneksi Drive" else "Izinkan Drive")}
                }
            }
        Field(name, { name = it.take(80) }, "Nama perangkat", modifier = Modifier.testTag("account-device-name"))
        OutlinedButton(onClick = { onRenameDevice(name.trim()) }, enabled = name.isNotBlank() && name != device.deviceName) { Text("Simpan nama perangkat") }
        Text("${device.brand} ${device.model}\nAndroid ${device.androidVersion}\nVersi aplikasi ${device.appVersion}")
        Text("ID instalasi\n${device.installationId}", style = MaterialTheme.typography.bodySmall)
        Text("ID instalasi dibuat secara acak dan dapat berubah setelah instal ulang atau reset data. Nama dan model perangkat dilaporkan aplikasi dan bukan bukti identitas perangkat.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Button(onClick = { logout = true }, modifier = Modifier.fillMaxWidth()) { Text("Keluar dari akun") }
    }
    if (logout) ConfirmAction("Keluar dari akun?", "Anda perlu masuk kembali untuk mengelola katalog.",
        onConfirm = { logout = false; onLogout() }, onDismiss = { logout = false })
}
