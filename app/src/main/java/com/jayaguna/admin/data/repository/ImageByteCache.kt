package com.jayaguna.admin.data.repository

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ImageCacheDiagnostics(
    val fetchesStarted: Long, val memoryHits: Long, val diskHits: Long,
    val coalescedReads: Long, val inFlight: Int,
)

class ImageCacheSessionChangedException : IOException("Sesi gambar sudah berubah. Buka kembali halaman setelah masuk.")

/** Bounded original-byte cache. A session change fences both in-flight reads and disk writes. */
internal class ImageByteCache(
    private val directory: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val memoryLimit: Long = 16L * 1024 * 1024,
    private val diskLimit: Long = 64L * 1024 * 1024,
    private val maxEntryBytes: Int = 5 * 1024 * 1024,
) {
    private val monitor = Any()
    private val diskLock = Mutex()
    private val memory = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var memorySize = 0L
    private val pending = mutableMapOf<String, Deferred<ByteArray>>()
    private val revisions = mutableMapOf<String, Long>()
    private var uid: String? = null
    private val mutableIdentity = MutableStateFlow<String?>(null)
    val identity = mutableIdentity.asStateFlow()
    private var diskBarrier: Deferred<Unit>? = null
    private var fetches = 0L
    private var memoryReads = 0L
    private var diskReads = 0L
    private var sharedReads = 0L
    private data class ReadTicket(val key: String, val revision: Long, val deferred: Deferred<ByteArray>)

    fun setSession(userId: String?, retainDisk: Boolean = false) = synchronized(monitor) {
        uid = userId
        mutableIdentity.value = userId?.let { "$it:${UUID.randomUUID()}" }
        pending.values.toList().forEach { it.cancel() }
        pending.clear()
        memory.clear(); memorySize = 0
        revisions.clear()
        enqueueDiskChange {
            directory.mkdirs()
            val prefix = userId?.let(::ownerPrefix)
            directory.listFiles()?.filter { it.isFile && (!retainDisk || prefix == null || !it.name.startsWith(prefix) || !it.name.endsWith(".img")) }
                ?.forEach { it.delete() }
            trimDisk()
        }
    }

    fun clearMemory() = synchronized(monitor) { memory.clear(); memorySize = 0L }

    fun invalidate(path: String) = synchronized(monitor) {
        val owner = uid ?: return@synchronized
        val key = fileName(owner, path)
        memory.remove(key)?.let { memorySize -= it.size }
        revisions[key] = (revisions[key] ?: 0) + 1
        pending.remove(key)?.cancel()
        enqueueDiskChange { File(directory, key).delete() }
    }

    fun diagnostics(): ImageCacheDiagnostics = synchronized(monitor) {
        ImageCacheDiagnostics(fetches, memoryReads, diskReads, sharedReads, pending.size)
    }

    suspend fun get(path: String, expectedIdentity: String, fetch: suspend () -> ByteArray): ByteArray {
        val request = synchronized(monitor) {
            requireSession(expectedIdentity)
            val owner = requireNotNull(uid)
            val key = fileName(owner, path)
            memory[key]?.let { memoryReads++; return it.copyOf() }
            val revision = revisions[key] ?: 0
            pending[key]?.let { sharedReads++; return@synchronized ReadTicket(key, revision, it) }
            val barrier = diskBarrier
            lateinit var deferred: Deferred<ByteArray>
            deferred = scope.async(start = CoroutineStart.LAZY) {
                barrier?.await()
                val fromDisk = diskLock.withLock {
                    synchronized(monitor) { requireCurrent(expectedIdentity, key, revision) }
                    readDisk(key)
                }
                if (fromDisk != null) {
                    synchronized(monitor) {
                        requireCurrent(expectedIdentity, key, revision)
                        diskReads++
                        putMemory(key, fromDisk)
                    }
                    fromDisk
                } else {
                    synchronized(monitor) { requireCurrent(expectedIdentity, key, revision); fetches++ }
                    val bytes = fetch()
                    if (bytes.isEmpty() || bytes.size > maxEntryBytes) throw IOException("Ukuran gambar tidak dapat disimpan pada cache.")
                    diskLock.withLock {
                        synchronized(monitor) { requireCurrent(expectedIdentity, key, revision) }
                        writeDisk(key, bytes)
                    }
                    synchronized(monitor) { requireCurrent(expectedIdentity, key, revision); putMemory(key, bytes) }
                    bytes
                }
            }
            pending[key] = deferred
            deferred.invokeOnCompletion { synchronized(monitor) { if (pending[key] === deferred) pending.remove(key) } }
            deferred.start()
            ReadTicket(key, revision, deferred)
        }
        val result = request.deferred.await()
        synchronized(monitor) { requireCurrent(expectedIdentity, request.key, request.revision) }
        // Never let a consumer mutate the cache or another coalesced consumer's bytes.
        return result.copyOf()
    }

    fun close() { scope.cancel() }

    private fun requireSession(expected: String) {
        if (expected != mutableIdentity.value || uid == null) throw ImageCacheSessionChangedException()
    }
    private fun requireCurrent(expected: String, key: String, revision: Long) {
        requireSession(expected)
        if ((revisions[key] ?: 0) != revision) throw ImageCacheSessionChangedException()
    }
    private fun putMemory(key: String, bytes: ByteArray) {
        if (bytes.size > memoryLimit) return
        memory.remove(key)?.let { memorySize -= it.size }
        memory[key] = bytes
        memorySize += bytes.size
        while (memorySize > memoryLimit) {
            val eldest = memory.entries.iterator().next()
            memorySize -= eldest.value.size; memory.remove(eldest.key)
        }
    }
    private fun enqueueDiskChange(change: () -> Unit) {
        val previous = diskBarrier
        diskBarrier = scope.async { previous?.await(); diskLock.withLock { change() } }
    }
    private fun readDisk(key: String): ByteArray? {
        val file = File(directory, key)
        if (!file.exists()) return null
        return try {
            if (file.length() !in (HEADER_BYTES + 1L)..(HEADER_BYTES + maxEntryBytes.toLong())) throw IOException("invalid-cache-size")
            val bytes = DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) throw IOException("invalid-cache-header")
                val length = input.readInt()
                if (length !in 1..maxEntryBytes || file.length() != HEADER_BYTES + length.toLong()) throw IOException("invalid-cache-size")
                val checksum = ByteArray(32).also(input::readFully)
                val content = ByteArray(length).also(input::readFully)
                if (!MessageDigest.isEqual(checksum, digest(content))) throw IOException("invalid-cache-checksum")
                content
            }
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (_: IOException) { file.delete(); null }
    }
    private fun writeDisk(key: String, bytes: ByteArray) {
        if (bytes.size + HEADER_BYTES > diskLimit) return
        val temporary = File(directory, key + "." + UUID.randomUUID() + ".part")
        try {
            directory.mkdirs()
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC); output.writeInt(bytes.size); output.write(digest(bytes)); output.write(bytes)
            }
            val destination = File(directory, key)
            if (!temporary.renameTo(destination)) { destination.delete(); if (!temporary.renameTo(destination)) return }
            destination.setLastModified(System.currentTimeMillis())
            trimDisk()
        } catch (_: IOException) { /* A full/unavailable cache must not fail a validated image download. */ }
        finally { temporary.delete() }
    }
    private fun trimDisk() {
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".img") }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files) { if (total <= diskLimit) break; val size = file.length(); if (file.delete()) total -= size }
    }
    private fun ownerPrefix(owner: String) = digest(owner.toByteArray()).hex().take(24) + "_"
    private fun fileName(owner: String, path: String) = ownerPrefix(owner) + digest(path.toByteArray()).hex() + ".img"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object { const val MAGIC = 0x4A545031; const val HEADER_BYTES = 40L }
}
