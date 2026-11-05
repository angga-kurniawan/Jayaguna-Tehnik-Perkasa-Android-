package com.jayaguna.admin.core.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun PageHeading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.padding(bottom = 6.dp).width(26.dp).height(3.dp).background(MaterialTheme.colorScheme.primary))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorNotice(error: String?, onRetry: (() -> Unit)? = null) {
    if (error == null) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Coba lagi") }
        }
    }
}

@Composable
fun EmptyNotice(title: String, detail: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ConfirmAction(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Ya, lanjutkan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } })
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true,
          singleLine: Boolean = true, error: String? = null, modifier: Modifier = Modifier) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        enabled = enabled, singleLine = singleLine, isError = error != null,
        supportingText = if (error != null) ({ Text(error) }) else null,
        modifier = modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(label: String, value: String, choices: List<Pair<String, String>>, onChange: (String) -> Unit,
              enabled: Boolean = true) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (key, title) ->
                FilterChip(selected = key == value, onClick = { onChange(key) }, enabled = enabled,
                    label = { Text(title) })
            }
        }
    }
}

@Composable
fun SaveProgress(busy: Boolean, progress: Float?) {
    if (busy) {
        val uploading = progress != null && progress < 1f
        LoadingNotice(if (uploading) "Mengunggah gambar…" else "Menyimpan perubahan…",
            if (uploading) "Tunggu hingga unggahan dan penyimpanan selesai." else "Menunggu konfirmasi penyimpanan.",
            progress.takeIf { uploading })
    }
}

/** Compatibility for inline feedback; loading lists supply their own page structure. */
@Composable
fun LoadingNotice(message: String, detail: String? = null, progress: Float? = null,
                  modifier: Modifier = Modifier) {
    LoadingStatus(message, detail, progress, modifier.padding(16.dp))
}

/** Saving is modal and bounded; read-only loading stays inside the relevant list or section. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun LoadingFrame(busy: Boolean, message: String, detail: String? = null, empty: Boolean = false,
                 progress: Float? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        content()
    }
    if (busy) Dialog(onDismissRequest = {}, properties = DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        OutlinedCard(Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().testTag("saving-progress")) {
            LoadingStatus(message, detail, progress, Modifier.padding(24.dp))
        }
    }
}
