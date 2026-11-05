package com.jayaguna.admin.data.repository
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import com.jayaguna.admin.data.drive.NativeDriveService
import com.jayaguna.admin.core.security.BackendException
import com.google.firebase.auth.FirebaseAuth
import java.io.File

class MediaRepository(private val storage: FirebaseStorage, private val writer: NativeCatalogWriter,
                      private val drive: NativeDriveService, private val auth: FirebaseAuth, cacheDir: File) {
    private val cache = ImageByteCache(File(cacheDir, "catalog-images-v1"))
    private var knownUid = auth.currentUser?.uid
    private val sessionLock = Any()
    val cacheIdentity = cache.identity
    private val authListener = FirebaseAuth.AuthStateListener { synchronizeSession() }

    init {
        cache.setSession(knownUid, retainDisk = true)
        auth.addAuthStateListener(authListener)
    }
    private fun synchronizeSession() = synchronized(sessionLock) {
        val uid = auth.currentUser?.uid
        if (uid != knownUid) { knownUid = uid; cache.setSession(uid) }
    }
    fun stableCacheKey(path: String): String? { synchronizeSession(); return cacheIdentity.value?.let { "$it:$path" } }
    fun clearMemoryCache() = cache.clearMemory()
    fun diagnostics() = cache.diagnostics()
    fun invalidate(path: String) { synchronizeSession(); cache.invalidate(path) }
    fun clear() = synchronized(sessionLock) { knownUid = auth.currentUser?.uid; cache.setSession(knownUid) }

    suspend fun bytes(path: String): ByteArray {
        synchronizeSession()
        val identity = cacheIdentity.value ?: throw BackendException("unauthenticated", "Silakan masuk kembali.")
        return bytes(path, identity)
    }
    suspend fun bytes(path: String, expectedIdentity: String): ByteArray {
        synchronizeSession()
        if (expectedIdentity != cacheIdentity.value) throw BackendException("unauthenticated", "Sesi gambar sudah berubah.")
        return try { cache.get(path, expectedIdentity) { download(path) } }
        catch (_: ImageCacheSessionChangedException) { throw BackendException("unauthenticated", "Sesi gambar sudah berubah.") }
    }
    private suspend fun download(path: String): ByteArray {
        if (!path.startsWith("drive/")) return storage.reference.child(path).getBytes(5L * 1024 * 1024).await()
        val asset = writer.assetForRead(path)
        if (asset.status !in setOf("uploaded", "attached", "detached")) throw BackendException("not-found", "Gambar tidak tersedia.")
        return drive.download(asset.id, asset.entityType, asset.sha256)
    }
}
