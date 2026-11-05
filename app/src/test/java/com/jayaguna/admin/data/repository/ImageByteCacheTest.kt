package com.jayaguna.admin.data.repository

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageByteCacheTest {
    private val directories = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()
    private fun directory() = Files.createTempDirectory("jtp-image-cache-").toFile().also { directories += it }
    private fun cache(scope: CoroutineScope, directory: File = directory(), memory: Long = 1024, disk: Long = 4096): ImageByteCache =
        ImageByteCache(directory, scope, memory, disk, 512).also { it.setSession("owner", retainDisk = true) }
    private fun scope(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
    @After fun cleanup() { scopes.forEach { it.cancel() }; directories.forEach { it.deleteRecursively() } }

    @Test fun repeatedReadersShareOneDownloadAndCallerCancellationDoesNotCancelIt() = runTest {
        val cache = cache(scope(StandardTestDispatcher(testScheduler)))
        val network = CompletableDeferred<ByteArray>()
        var fetches = 0
        val identity = requireNotNull(cache.identity.value)
        val first = async { cache.get("drive/one", identity) { fetches++; network.await() } }
        runCurrent()
        val second = async { cache.get("drive/one", identity) { error("second fetch") } }
        runCurrent()
        first.cancel()
        network.complete(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), second.await())
        assertEquals(1, fetches)
        assertEquals(1L, cache.diagnostics().coalescedReads)
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("drive/one", identity) { error("cache missed") })
        assertEquals(1L, cache.diagnostics().memoryHits)
    }

    @Test fun restartAndMemoryEvictionUseDiskWithoutRefetching() = runTest {
        val root = directory()
        val firstCache = cache(scope(StandardTestDispatcher(testScheduler)), root, memory = 3)
        val firstIdentity = requireNotNull(firstCache.identity.value)
        firstCache.get("drive/one", firstIdentity) { byteArrayOf(1, 2, 3) }
        firstCache.get("drive/two", firstIdentity) { byteArrayOf(4, 5, 6) }
        assertArrayEquals(byteArrayOf(1, 2, 3), firstCache.get("drive/one", firstIdentity) { error("memory eviction must use disk") })
        assertEquals(1L, firstCache.diagnostics().diskHits)
        val restarted = cache(scope(StandardTestDispatcher(testScheduler)), root, memory = 3)
        assertNotEquals(firstIdentity, restarted.identity.value)
        assertArrayEquals(byteArrayOf(4, 5, 6), restarted.get("drive/two", requireNotNull(restarted.identity.value)) { error("restart must use disk") })
        assertEquals(0L, restarted.diagnostics().fetchesStarted)
        assertEquals(1L, restarted.diagnostics().diskHits)
    }

    @Test fun failedDownloadsAreNotCachedAndCorruptDiskIsFetchedAgain() = runTest {
        val root = directory()
        val cache = cache(scope(StandardTestDispatcher(testScheduler)), root)
        val identity = requireNotNull(cache.identity.value)
        assertTrue(runCatching { cache.get("drive/one", identity) { throw java.io.IOException("network offline") } }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2), cache.get("drive/one", identity) { byteArrayOf(1, 2) })
        cache.clearMemory()
        root.listFiles()!!.single { it.extension == "img" }.writeBytes(byteArrayOf(9, 9))
        assertArrayEquals(byteArrayOf(3, 4), cache.get("drive/one", identity) { byteArrayOf(3, 4) })
        assertEquals(3L, cache.diagnostics().fetchesStarted)
        assertEquals(0L, cache.diagnostics().diskHits)
    }

    @Test fun logoutRejectsLateUncancellableDownloadsAndPurgesThePreviousSession() = runTest {
        val root = directory()
        val cache = cache(scope(StandardTestDispatcher(testScheduler)), root)
        val oldIdentity = requireNotNull(cache.identity.value)
        cache.get("drive/existing", oldIdentity) { byteArrayOf(1) }
        val network = CompletableDeferred<ByteArray>()
        val oldRead = async {
            runCatching { cache.get("drive/late", oldIdentity) { withContext(NonCancellable) { network.await() } } }
        }
        runCurrent()
        cache.setSession(null)
        cache.setSession("admin")
        network.complete(byteArrayOf(9))
        advanceUntilIdle()
        assertTrue(oldRead.await().isFailure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        assertTrue(runCatching { cache.get("drive/late", oldIdentity) { error("old session started fetch") } }.exceptionOrNull() is ImageCacheSessionChangedException)
        assertArrayEquals(byteArrayOf(2), cache.get("drive/existing", requireNotNull(cache.identity.value)) { byteArrayOf(2) })
    }

    @Test fun invalidationPreventsAnOlderReadRepopulatingTheSameImage() = runTest {
        val cache = cache(scope(StandardTestDispatcher(testScheduler)))
        val identity = requireNotNull(cache.identity.value)
        val oldNetwork = CompletableDeferred<ByteArray>()
        val old = async { runCatching { cache.get("drive/one", identity) { withContext(NonCancellable) { oldNetwork.await() } } } }
        runCurrent()
        cache.invalidate("drive/one")
        val updated = async { cache.get("drive/one", identity) { byteArrayOf(7) } }
        oldNetwork.complete(byteArrayOf(1))
        assertArrayEquals(byteArrayOf(7), updated.await())
        advanceUntilIdle()
        assertTrue(old.await().isFailure)
        assertArrayEquals(byteArrayOf(7), cache.get("drive/one", identity) { error("must retain updated value") })
    }

    @Test fun invalidationAfterProducerCompletesStillRejectsTheWaitingConsumer() = runTest {
        val cache = cache(scope(UnconfinedTestDispatcher(testScheduler)))
        val identity = requireNotNull(cache.identity.value)
        val network = CompletableDeferred<ByteArray>()
        val waiting = async { runCatching { cache.get("drive/one", identity) { network.await() } } }
        runCurrent()
        // Producer runs inline; the standard-dispatcher consumer is still queued.
        network.complete(byteArrayOf(1))
        assertEquals(0, cache.diagnostics().inFlight)
        assertFalse(waiting.isCompleted)
        cache.invalidate("drive/one")
        runCurrent()
        assertTrue(waiting.await().exceptionOrNull() is ImageCacheSessionChangedException)
        assertArrayEquals(byteArrayOf(2), cache.get("drive/one", identity) { byteArrayOf(2) })
    }

    @Test fun diskBudgetEvictsTheOldestEntryAndReturnedBytesCannotMutateCache() = runTest {
        val root = directory()
        val cache = cache(scope(StandardTestDispatcher(testScheduler)), root, memory = 2, disk = 84)
        val identity = requireNotNull(cache.identity.value)
        val original = cache.get("drive/one", identity) { byteArrayOf(1, 2) }
        original[0] = 9
        assertArrayEquals(byteArrayOf(1, 2), cache.get("drive/one", identity) { error("mutated cache") })
        root.listFiles()!!.single().setLastModified(1)
        cache.get("drive/two", identity) { byteArrayOf(3, 4) }
        root.listFiles()!!.filter { it.extension == "img" }.forEach { if (it.lastModified() != 1L) it.setLastModified(2) }
        cache.get("drive/three", identity) { byteArrayOf(5, 6) }
        assertTrue(root.listFiles()!!.sumOf { it.length() } <= 84L)
        cache.clearMemory()
        assertArrayEquals(byteArrayOf(8, 8), cache.get("drive/one", identity) { byteArrayOf(8, 8) })
        assertEquals(4L, cache.diagnostics().fetchesStarted)
    }
}
