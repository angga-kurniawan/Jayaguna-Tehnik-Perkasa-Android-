package com.jayaguna.admin.core.components

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.memory.MemoryCache
import coil.request.Options
import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.data.repository.MediaRepository
import okio.Buffer

/** Immutable Drive media paths identify image contents; the session isolates private images. */
data class CatalogImage(val path: String, val sessionKey: String)

fun catalogMemoryCacheKey(sessionKey: String, path: String): String = "catalog:$sessionKey:$path"

/** One loader per application, shared by list, detail, editor and portfolio images. */
fun createCatalogImageLoader(context: Context, repository: MediaRepository): ImageLoader {
    val application = context.applicationContext
    val budget = minOf(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 5).toInt()
    val memory = MemoryCache.Builder(application).maxSizeBytes(budget)
        .weakReferencesEnabled(false).build()
    return ImageLoader.Builder(application)
        .memoryCache(SessionImageMemoryCache(memory) { repository.cacheIdentity.value })
        // Original image bytes have their own private bounded repository cache.
        .diskCache(null)
        .components { add(CatalogImageFetcher.Factory(repository)) }
        .crossfade(false)
        .build()
}

private class CatalogImageFetcher(
    private val data: CatalogImage,
    private val repository: MediaRepository,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): SourceResult {
        val bytes = repository.bytes(data.path, data.sessionKey)
        if (repository.cacheIdentity.value != data.sessionKey) {
            throw BackendException("unauthenticated", "Sesi gambar sudah berakhir.")
        }
        return SourceResult(ImageSource(Buffer().write(bytes), options.context), null, DataSource.MEMORY)
    }

    class Factory(private val repository: MediaRepository) : Fetcher.Factory<CatalogImage> {
        override fun create(data: CatalogImage, options: Options, imageLoader: ImageLoader): Fetcher =
            CatalogImageFetcher(data, repository, options)
    }
}

/** A decode completing after logout cannot repopulate the previous session's bitmap cache. */
private class SessionImageMemoryCache(
    private val delegate: MemoryCache,
    private val identity: () -> String?,
) : MemoryCache {
    private fun allowed(key: MemoryCache.Key): Boolean = identity()?.let {
        key.key.startsWith("catalog:$it:")
    } == true

    override val size: Int get() = delegate.size
    override val maxSize: Int get() = delegate.maxSize
    override val keys: Set<MemoryCache.Key> get() = delegate.keys.filterTo(mutableSetOf(), ::allowed)
    override fun get(key: MemoryCache.Key): MemoryCache.Value? = if (allowed(key)) delegate[key] else null
    override fun set(key: MemoryCache.Key, value: MemoryCache.Value) {
        if (allowed(key)) {
            delegate[key] = value
            // Logout may race between the first identity check and the write.
            if (!allowed(key)) delegate.remove(key)
        }
    }
    override fun remove(key: MemoryCache.Key): Boolean = delegate.remove(key)
    override fun clear() = delegate.clear()
    override fun trimMemory(level: Int) = delegate.trimMemory(level)
}
