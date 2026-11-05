package com.jayaguna.admin.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.jayaguna.admin.data.model.Media
import kotlin.math.roundToInt

/** Uses the same session image loader and byte cache as the thumbnail. */
@Composable
fun ImagePreviewDialog(media: Media, contentDescription: String, onDismiss: () -> Unit) {
    var scale by rememberSaveable(media.storagePath) { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable(media.storagePath) { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable(media.storagePath) { mutableFloatStateOf(0f) }
    var viewport by remember(media.storagePath) { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(media.storagePath) { mutableStateOf(IntSize.Zero) }

    fun moveTo(nextScale: Float, nextX: Float, nextY: Float) {
        scale = nextScale.coerceIn(1f, 4f)
        val fit = if (imageSize.width > 0 && imageSize.height > 0)
            minOf(viewport.width.toFloat() / imageSize.width, viewport.height.toFloat() / imageSize.height) else 1f
        val fittedWidth = if (imageSize.width > 0) imageSize.width * fit else viewport.width.toFloat()
        val fittedHeight = if (imageSize.height > 0) imageSize.height * fit else viewport.height.toFloat()
        val maxX = ((fittedWidth * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fittedHeight * scale - viewport.height) / 2f).coerceAtLeast(0f)
        offsetX = nextX.coerceIn(-maxX, maxX)
        offsetY = nextY.coerceIn(-maxY, maxY)
    }
    fun zoomTo(nextScale: Float, focus: Offset = Offset.Zero, pan: Offset = Offset.Zero) {
        val next = nextScale.coerceIn(1f, 4f)
        val ratio = next / scale
        moveTo(next, offsetX * ratio + focus.x * (1f - ratio) + pan.x,
            offsetY * ratio + focus.y * (1f - ratio) + pan.y)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = true, dismissOnClickOutside = false,
    )) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val statusWasLight = controller?.isAppearanceLightStatusBars
            val navigationWasLight = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
            onDispose {
                if (statusWasLight != null) controller?.isAppearanceLightStatusBars = statusWasLight
                if (navigationWasLight != null) controller?.isAppearanceLightNavigationBars = navigationWasLight
            }
        }
        Surface(modifier = Modifier.fillMaxSize().testTag("image-preview"), color = Color(0xFF101318)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF1D232B)).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(contentDescription.ifBlank { "Preview gambar" }, color = Color.White,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("image-preview-close")) {
                        Icon(Icons.Outlined.Close, "Tutup preview gambar", tint = Color.White)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .testTag("image-preview-viewport")
                    .semantics { stateDescription = "Zoom ${(scale * 100).roundToInt()}%" }
                    .onSizeChanged { size -> viewport = size; moveTo(scale, offsetX, offsetY) }
                    .pointerInput(media.storagePath) {
                        detectTapGestures(onDoubleTap = { point ->
                            if (scale > 1f) moveTo(1f, 0f, 0f)
                            else zoomTo(2f, point - Offset(viewport.width / 2f, viewport.height / 2f))
                        })
                    }
                    .pointerInput(media.storagePath) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            zoomTo(scale * zoom, centroid - Offset(viewport.width / 2f, viewport.height / 2f), pan)
                        }
                    }, contentAlignment = Alignment.Center) {
                    StorageImage(media, contentDescription,
                        Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = offsetX; translationY = offsetY
                        }, fit = true, previewEnabled = false, onImageSize = { size ->
                            imageSize = size
                            moveTo(scale, offsetX, offsetY)
                        })
                }
                Column(Modifier.fillMaxWidth().background(Color(0xFF1D232B)).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row {
                            IconButton(onClick = { zoomTo(scale - 0.5f) }, enabled = scale > 1f,
                                modifier = Modifier.size(48.dp).testTag("image-preview-zoom-out")) {
                                Icon(Icons.Outlined.Remove, "Perkecil gambar",
                                    tint = if (scale > 1f) Color.White else Color(0xFF69727C))
                            }
                            IconButton(onClick = { zoomTo(scale + 0.5f) }, enabled = scale < 4f,
                                modifier = Modifier.size(48.dp).testTag("image-preview-zoom-in")) {
                                Icon(Icons.Outlined.Add, "Perbesar gambar",
                                    tint = if (scale < 4f) Color.White else Color(0xFF69727C))
                            }
                        }
                        Text("${(scale * 100).roundToInt()}%", color = Color.White,
                            style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { moveTo(1f, 0f, 0f) }, enabled = scale > 1f,
                            modifier = Modifier.size(48.dp).testTag("image-preview-zoom-reset")) {
                            Icon(Icons.Outlined.Refresh, "Atur ulang zoom",
                                tint = if (scale > 1f) Color.White else Color(0xFF69727C))
                        }
                    }
                    Text("Cubit untuk zoom · Ketuk dua kali untuk memperbesar", color = Color(0xFFB6BEC7),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
