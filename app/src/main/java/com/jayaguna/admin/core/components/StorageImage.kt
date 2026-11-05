package com.jayaguna.admin.core.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.repository.MediaRepository

val LocalMediaRepository = staticCompositionLocalOf<MediaRepository?> { null }
val LocalCatalogImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
fun StorageImage(media: Media?, contentDescription: String, modifier: Modifier = Modifier, fit: Boolean = false,
                 previewEnabled: Boolean = true, onImageSize: ((IntSize) -> Unit)? = null) {
    val repository = LocalMediaRepository.current
    val context = LocalContext.current
    val loader = LocalCatalogImageLoader.current ?: context.imageLoader
    val sessionKey = if (repository != null) repository.cacheIdentity.collectAsStateWithLifecycle().value else null
    val path = media?.storagePath.orEmpty()
    val local = path.startsWith("content://")
    var attempt by remember(path, sessionKey) { mutableIntStateOf(0) }
    var previewOpen by rememberSaveable(path, sessionKey) { mutableStateOf(false) }
    val canPreview = previewEnabled && path.isNotBlank() && (local || repository != null && sessionKey != null)

    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            path.isBlank() -> Text("Belum ada gambar")
            !local && (repository == null || sessionKey == null) -> Text("Masuk kembali untuk melihat gambar.")
            else -> key(path, sessionKey, attempt) {
                val memoryKey = remember(path, sessionKey) {
                    catalogMemoryCacheKey(sessionKey ?: "local-preview", path)
                }
                val cached = remember(loader, memoryKey) { loader.memoryCache?.get(MemoryCache.Key(memoryKey)) != null }
                var ready by remember { mutableStateOf(cached) }
                var loading by remember { mutableStateOf(!cached) }
                var failed by remember { mutableStateOf(false) }
                val model: Any = if (local) path else CatalogImage(path, requireNotNull(sessionKey))
                val request = remember(model, memoryKey) {
                    ImageRequest.Builder(context).data(model).memoryCacheKey(memoryKey)
                        .placeholderMemoryCacheKey(memoryKey).crossfade(false).build()
                }
                AsyncImage(model = request, imageLoader = loader, contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize()
                        .then(if (canPreview) Modifier.clickable(onClickLabel = "Buka preview gambar", role = Role.Button) { previewOpen = true } else Modifier)
                        .then(if (ready) Modifier.testTag("media-ready:$path") else Modifier),
                    contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                    onLoading = { state ->
                        // A cached thumbnail is already visible while a larger decode is prepared.
                        loading = state.painter == null
                        ready = !loading
                        failed = false
                    },
                    onSuccess = { state ->
                        ready = true; loading = false; failed = false
                        onImageSize?.invoke(IntSize(state.result.drawable.intrinsicWidth.coerceAtLeast(1), state.result.drawable.intrinsicHeight.coerceAtLeast(1)))
                    },
                    onError = { ready = false; loading = false; failed = true })
                if (loading) ImageLoadingIndicator(Modifier.testTag("media-loading:$path"))
                if (failed) TextButton(onClick = {
                    repository?.invalidate(path)
                    loader.memoryCache?.remove(MemoryCache.Key(memoryKey))
                    attempt++
                }) { Text("Muat ulang gambar") }
            }
        }
    }
    if (previewOpen && canPreview && media != null) {
        ImagePreviewDialog(media, contentDescription) { previewOpen = false }
    }
}

@Composable
private fun ImageLoadingIndicator(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Column(Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("Memuat gambar…", style = MaterialTheme.typography.labelSmall)
        }
    }
}
