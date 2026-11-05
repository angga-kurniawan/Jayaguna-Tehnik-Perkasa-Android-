package com.jayaguna.admin.features.portfolio.controller

import androidx.lifecycle.ViewModelStore
import com.google.firebase.firestore.DocumentSnapshot
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.PortfolioImage
import com.jayaguna.admin.data.repository.Page
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioControllerTest {
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() {
        stores.forEach(ViewModelStore::clear)
        stores.clear()
        Dispatchers.resetMain()
    }

    private fun controller(gateway: FakeGateway) = PortfolioController(gateway).also {
        stores += ViewModelStore().apply { put("portfolio", it) }
    }

    @Test fun batchRetryKeepsSuccessfulImagesAndReusesOnlyTheFailedRequest() = runTest {
        val gateway = FakeGateway()
        var failSecond = true
        gateway.saveResult = { item, _ -> if (item.image?.storagePath == "content://second" && failSecond) throw IOException("interrupted") }
        val controller = controller(gateway)
        controller.selectImages(listOf("content://first", "content://second"))
        val initial = controller.state.value.selected

        controller.uploadSelected()
        advanceUntilIdle()
        assertEquals(listOf(initial[1]), controller.state.value.selected)
        assertTrue(controller.state.value.canRetry)
        assertFalse(controller.state.value.saving)

        failSecond = false
        controller.retry()
        advanceUntilIdle()
        assertEquals(listOf(initial[0].requestId, initial[1].requestId, initial[1].requestId), gateway.saves.map { it.second })
        assertEquals(listOf(initial[0].id, initial[1].id, initial[1].id), gateway.saves.map { it.first.id })
        assertTrue(controller.state.value.selected.isEmpty())
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.canRetry)
    }

    @Test fun categoryCannotChangeWhileImagesAreSelectedOrAnUploadIsPending() = runTest {
        val saved = CompletableDeferred<Unit>()
        val gateway = FakeGateway().apply { saveResult = { _, _ -> saved.await() } }
        val controller = controller(gateway)
        controller.selectImages(listOf("content://photo"))
        controller.selectCategory("corporate")
        assertEquals("residential", controller.state.value.category)
        controller.uploadSelected()
        runCurrent()
        controller.selectCategory("corporate")
        controller.discardSelection()
        assertEquals("residential", controller.state.value.category)
        assertEquals(1, controller.state.value.selected.size)
        assertTrue(controller.state.value.saving)

        saved.complete(Unit)
        advanceUntilIdle()
        controller.selectCategory("corporate")
        advanceUntilIdle()
        assertEquals("corporate", controller.state.value.category)
        assertEquals("residential", gateway.saves.single().first.category)
    }

    @Test fun categorySwitchDiscardsAnUncancellableOldGalleryResponse() = runTest {
        val oldGallery = CompletableDeferred<Page<PortfolioImage>>()
        val newImage = PortfolioImage(id = "new", category = "corporate")
        val gateway = FakeGateway().apply {
            listResult = { category ->
                if (category == "residential") withContext(NonCancellable) { oldGallery.await() }
                else Page(listOf(newImage), null, false)
            }
        }
        val controller = controller(gateway)
        controller.load(true)
        runCurrent()
        controller.selectCategory("corporate")
        runCurrent()
        oldGallery.complete(Page(listOf(PortfolioImage(id = "stale")), null, false))
        advanceUntilIdle()
        assertEquals("corporate", controller.state.value.category)
        assertEquals(listOf(newImage), controller.state.value.items)
        assertFalse(controller.state.value.loading)
    }

    @Test fun replacementAndDeletionRetryPreserveTheOriginalRequestAndVersion() = runTest {
        val gateway = FakeGateway()
        var fail = true
        gateway.saveResult = { _, _ -> if (fail) throw IOException("uncertain save") }
        gateway.deleteResult = { _, _ -> if (fail) throw IOException("uncertain delete") }
        val controller = controller(gateway)
        val original = PortfolioImage(id = "image", category = "corporate", image = Media(storagePath = "drive/old"), version = 3)
        controller.replace(original, "content://replacement")
        advanceUntilIdle()
        fail = false
        controller.retry()
        advanceUntilIdle()
        assertEquals(2, gateway.saves.size)
        assertEquals(gateway.saves[0], gateway.saves[1])
        assertEquals(3L, gateway.saves[1].first.version)

        fail = true
        controller.delete(original)
        advanceUntilIdle()
        fail = false
        controller.retry()
        advanceUntilIdle()
        assertEquals(2, gateway.deletes.size)
        assertEquals(gateway.deletes[0], gateway.deletes[1])
        assertNotEquals(gateway.saves[0].second, gateway.deletes[0].second)
    }

    @Test fun ensureLoadedReusesConfirmedGalleryAndRefreshFailureRetainsIt() = runTest {
        val image = PortfolioImage(id = "confirmed")
        val gateway = FakeGateway().apply { listResult = { Page(listOf(image), null, false) } }
        val controller = controller(gateway)
        controller.ensureLoaded()
        controller.ensureLoaded()
        advanceUntilIdle()
        controller.ensureLoaded()
        advanceUntilIdle()
        assertEquals(listOf("residential"), gateway.lists)
        gateway.listResult = { throw IOException("offline") }
        controller.load(true)
        runCurrent()
        assertEquals(listOf(image), controller.state.value.items)
        advanceUntilIdle()
        assertEquals(listOf(image), controller.state.value.items)
        assertTrue(controller.state.value.loaded)
        assertNotNull(controller.state.value.listError)
    }

    @Test fun categoryBackNavigationRestoresCachedGalleryWithoutAnotherQuery() = runTest {
        val gateway = FakeGateway().apply { listResult = { category -> Page(listOf(PortfolioImage(id = category, category = category)), null, false) } }
        val controller = controller(gateway)
        controller.ensureLoaded()
        advanceUntilIdle()
        controller.selectCategory("corporate")
        advanceUntilIdle()
        controller.selectCategory("residential")
        advanceUntilIdle()
        assertEquals(listOf("residential", "corporate"), gateway.lists)
        assertEquals("residential", controller.state.value.items.single().id)
    }

    @Test fun processRestoreKeepsRemainingBatchRequestIdsWithoutAutomaticallyUploading() = runTest {
        val gateway = FakeGateway().apply { saveResult = { item, _ -> if (item.image?.storagePath == "content://second") throw IOException("uncertain") } }
        val old = controller(gateway)
        old.selectCategory("corporate")
        advanceUntilIdle()
        old.selectImages(listOf("content://first", "content://second"))
        old.uploadSelected()
        advanceUntilIdle()
        val remaining = old.state.value.selected
        val restoredGateway = FakeGateway()
        val restored = controller(restoredGateway)
        restored.restoreUiState(old.snapshotUiState())
        advanceUntilIdle()
        assertEquals("corporate", restored.state.value.category)
        assertEquals(remaining, restored.state.value.selected)
        assertFalse(restored.state.value.saving)
        assertTrue(restoredGateway.saves.isEmpty())
        assertEquals(0, restored.completedChanges.value)
        restored.uploadSelected()
        advanceUntilIdle()
        assertEquals(remaining.single().requestId, restoredGateway.saves.single().second)
        assertEquals(1, restored.completedChanges.value)
    }

    @Test fun interruptedReplacementRestoresRequestAndVersionButRequiresRetry() = runTest {
        val oldGateway = FakeGateway().apply { saveResult = { _, _ -> throw IOException("uncertain") } }
        val old = controller(oldGateway)
        old.selectCategory("corporate")
        advanceUntilIdle()
        val original = PortfolioImage(UUID.randomUUID().toString(), "corporate", Media(storagePath = "drive/old"), version = 4)
        old.replace(original, "content://replacement")
        advanceUntilIdle()
        val restoredGateway = FakeGateway()
        val restored = controller(restoredGateway)
        restored.restoreUiState(old.snapshotUiState())
        advanceUntilIdle()
        assertTrue(restored.state.value.canRetry)
        assertFalse(restored.state.value.saving)
        assertTrue(restoredGateway.saves.isEmpty())
        restored.retry()
        advanceUntilIdle()
        assertEquals(oldGateway.saves.single(), restoredGateway.saves.single())
    }

    @Test fun rotationSnapshotCannotOverwriteAControllerThatHasAlreadyBeenUsed() = runTest {
        val controller = controller(FakeGateway())
        controller.selectCategory("corporate")
        advanceUntilIdle()
        controller.restoreUiState(PortfolioUiStateCodec.encode(RestoredPortfolioState("residential", emptyList(), null)))
        assertEquals("corporate", controller.state.value.category)
    }

    private class FakeGateway : PortfolioGateway {
        val saves = mutableListOf<Pair<PortfolioImage, String>>()
        val deletes = mutableListOf<Pair<PortfolioImage, String>>()
        val lists = mutableListOf<String>()
        var listResult: suspend (String) -> Page<PortfolioImage> = { Page(emptyList(), null, false) }
        var saveResult: suspend (PortfolioImage, String) -> Unit = { _, _ -> }
        var deleteResult: suspend (PortfolioImage, String) -> Unit = { _, _ -> }
        override suspend fun list(category: String, cursor: DocumentSnapshot?): Page<PortfolioImage> {
            lists += category
            return listResult(category)
        }
        override suspend fun save(item: PortfolioImage, requestId: String, progress: (Float) -> Unit) {
            saves += item to requestId
            saveResult(item, requestId)
        }
        override suspend fun delete(item: PortfolioImage, requestId: String) {
            deletes += item to requestId
            deleteResult(item, requestId)
        }
    }
}
