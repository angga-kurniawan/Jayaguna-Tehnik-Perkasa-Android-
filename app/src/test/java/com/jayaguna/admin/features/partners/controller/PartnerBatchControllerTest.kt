package com.jayaguna.admin.features.partners.controller

import androidx.lifecycle.ViewModelStore
import com.jayaguna.admin.data.model.Partner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.FileNotFoundException

@OptIn(ExperimentalCoroutinesApi::class)
class PartnerBatchControllerTest {
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { stores.forEach(ViewModelStore::clear); stores.clear(); Dispatchers.resetMain() }
    private fun controller(gateway: FakeGateway = FakeGateway()) = PartnerBatchController(gateway).also {
        stores += ViewModelStore().apply { put("partner-batch", it) }
    }
    private fun selection(uri: String, name: String = "Perusahaan.png") = PartnerLogoSelection("content://$uri", name)

    @Test fun selectingMultipleLogosCreatesIndependentDraftsAndDeduplicatesUris() = runTest {
        val controller = controller()
        controller.selectLogos(listOf(selection("first", "PT Jaya.PNG"), selection("second", "\n\t.png"), selection("first", "Other.png")))
        val selected = controller.state.value.selected
        assertEquals(2, selected.size)
        assertEquals("PT Jaya", selected[0].partner.name)
        assertEquals("Logo PT Jaya", selected[0].partner.altText)
        assertEquals("Perusahaan mitra 2", selected[1].partner.name)
        assertNotEquals(selected[0].partner.id, selected[1].partner.id)
        assertNotEquals(selected[0].requestId, selected[1].requestId)
        controller.selectLogos(listOf(selection("first")))
        assertEquals(selected, controller.state.value.selected)
        assertEquals(0, controller.completedChanges.value)
    }

    @Test fun excessOrNonContentSelectionsDoNotDestroyExistingDrafts() = runTest {
        val controller = controller()
        controller.selectLogos(listOf(selection("first")))
        val old = controller.state.value.selected
        controller.selectLogos((1..8).map { selection("addition$it") })
        assertEquals(old, controller.state.value.selected)
        assertNotNull(controller.state.value.error)
        controller.selectLogos(listOf(PartnerLogoSelection("https://example.com/logo.png", "Remote")))
        assertEquals(old, controller.state.value.selected)
        controller.selectLogos((1..7).map { selection("addition$it") })
        assertEquals(8, controller.state.value.selected.size)
        assertNull(controller.state.value.error)
    }

    @Test fun everyDraftIsValidatedBeforeAnyLogoStartsUploading() = runTest {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        controller.selectLogos(listOf(selection("first"), selection("second")))
        controller.update(controller.state.value.selected.last().partner.copy(name = ""))
        controller.uploadSelected()
        advanceUntilIdle()
        assertTrue(gateway.saves.isEmpty())
        assertTrue(controller.state.value.selected.none(PendingPartnerDraft::locked))
        assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.saving)
    }

    @Test fun invalidWebsiteAndOversizedAlternativeTextAreRejectedBeforeUpload() = runTest {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        controller.selectLogos(listOf(selection("first")))
        val partner = controller.state.value.selected.single().partner
        for (invalid in listOf(partner.copy(website = "https://user:password@example.com"),
            partner.copy(website = "https://example.com bad"), partner.copy(altText = "x".repeat(251)),
            partner.copy(displayOrder = 1_000_001))) {
            controller.update(invalid)
            controller.uploadSelected()
            advanceUntilIdle()
            assertTrue(gateway.saves.isEmpty())
            assertNotNull(controller.state.value.error)
        }
    }

    @Test fun uncertainCommitRetriesExactRequestAndDoesNotRepeatConfirmedPartners() = runTest {
        val gateway = FakeGateway()
        val committed = linkedMapOf<String, Partner>()
        var loseSecondReply = true
        gateway.result = { partner, requestId ->
            committed[requestId]?.let { assertEquals(it, partner) }
            committed[requestId] = partner
            if (partner.logo?.storagePath == "content://second" && loseSecondReply) {
                loseSecondReply = false
                throw IOException("reply lost after commit")
            }
        }
        val controller = controller(gateway)
        controller.selectLogos(listOf(selection("first"), selection("second"), selection("third")))
        val original = controller.state.value.selected
        controller.uploadSelected()
        advanceUntilIdle()
        val remaining = controller.state.value.selected
        assertEquals(listOf(original[1].partner.id, original[2].partner.id), remaining.map { it.partner.id })
        assertTrue(remaining[0].locked)
        assertFalse(remaining[1].locked)
        assertEquals(1, controller.completedChanges.value)
        assertTrue(controller.state.value.canRetry)

        controller.update(remaining[0].partner.copy(name = "Must not change"))
        controller.removeSelection(remaining[0].partner.id)
        controller.selectLogos(listOf(selection("second", "Duplicate renamed.png")))
        assertEquals(remaining, controller.state.value.selected)
        controller.update(remaining[1].partner.copy(name = "Nama mitra ketiga"))
        controller.uploadSelected()
        advanceUntilIdle()
        assertEquals(gateway.saves[1], gateway.saves[2])
        assertEquals(listOf(original[0].requestId, original[1].requestId, original[1].requestId, original[2].requestId), gateway.saves.map { it.second })
        assertEquals(3, committed.size)
        assertEquals("Nama mitra ketiga", gateway.saves.last().first.name)
        assertEquals(3, controller.completedChanges.value)
        assertTrue(controller.state.value.selected.isEmpty())
        assertFalse(controller.state.value.canRetry)
        assertNull(controller.state.value.error)
    }

    @Test fun savingRejectsEditsRemovalDiscardAndDuplicateSubmission() = runTest {
        val reply = CompletableDeferred<Unit>()
        val gateway = FakeGateway().apply { result = { _, _ -> reply.await() } }
        val controller = controller(gateway)
        controller.selectLogos(listOf(selection("first")))
        controller.uploadSelected()
        controller.uploadSelected()
        runCurrent()
        val frozen = controller.state.value.selected.single()
        assertTrue(frozen.locked)
        controller.update(frozen.partner.copy(name = "Changed"))
        controller.removeSelection(frozen.partner.id)
        controller.discardSelection()
        controller.selectLogos(listOf(selection("second")))
        assertEquals(listOf(frozen), controller.state.value.selected)
        assertEquals(1, gateway.saves.size)
        reply.complete(Unit)
        advanceUntilIdle()
        assertTrue(controller.state.value.selected.isEmpty())
        assertEquals(1, controller.completedChanges.value)
    }

    @Test fun localImageFailureOnFirstAttemptAllowsRemovingAndReselectingTheInvalidLogo() = runTest {
        for (localError in listOf(IllegalArgumentException("Unsupported image"), FileNotFoundException("missing local image"))) {
            val gateway = FakeGateway().apply { result = { _, _ -> throw localError } }
            val controller = controller(gateway)
            controller.selectLogos(listOf(selection("bad"), selection("good")))
            val bad = controller.state.value.selected.first()
            val unattempted = controller.state.value.selected.last()
            controller.uploadSelected()
            advanceUntilIdle()
            assertFalse(controller.state.value.selected.first().locked)
            assertEquals(unattempted, controller.state.value.selected.last())
            controller.removeSelection(bad.partner.id)
            controller.selectLogos(listOf(selection("replacement")))
            assertEquals(2, controller.state.value.selected.size)
            assertTrue(controller.state.value.selected.none { it.requestId == bad.requestId })
            gateway.result = { _, _ -> }
            controller.uploadSelected()
            advanceUntilIdle()
            assertTrue(controller.state.value.selected.isEmpty())
            assertEquals(2, controller.completedChanges.value)
        }
    }

    @Test fun unreadableLocalUriOnRetryDoesNotUnlockAnEarlierUncertainCommit() = runTest {
        val gateway = FakeGateway().apply { result = { _, _ -> throw IOException("uncertain reply") } }
        val controller = controller(gateway)
        controller.selectLogos(listOf(selection("first")))
        controller.uploadSelected()
        advanceUntilIdle()
        val frozen = controller.state.value.selected.single()
        assertTrue(frozen.locked)
        gateway.result = { _, _ -> throw FileNotFoundException("URI permission expired") }
        controller.uploadSelected()
        advanceUntilIdle()
        assertEquals(listOf(frozen), controller.state.value.selected)
        controller.removeSelection(frozen.partner.id)
        assertEquals(listOf(frozen), controller.state.value.selected)
        assertEquals(0, controller.completedChanges.value)
    }

    @Test fun restoreRetainsUncertainMetadataAndIdsWithoutAutomaticReplay() = runTest {
        val gateway = FakeGateway().apply { result = { partner, _ -> if (partner.logo?.storagePath == "content://second") throw IOException("uncertain") } }
        val old = controller(gateway)
        old.selectLogos(listOf(selection("first"), selection("second")))
        val editable = old.state.value.selected.last().partner
        old.update(editable.copy(name = "  Nama perusahaan  ", altText = "  Logo perusahaan  ", website = "https://example.com", displayOrder = 42, active = false))
        old.uploadSelected()
        advanceUntilIdle()
        val pending = old.state.value.selected
        val newGateway = FakeGateway()
        val restored = controller(newGateway)
        restored.restoreUiState(old.snapshotUiState())
        advanceUntilIdle()
        assertEquals(pending, restored.state.value.selected)
        assertTrue(restored.state.value.canRetry)
        assertTrue(newGateway.saves.isEmpty())
        assertFalse(restored.state.value.saving)
        assertEquals(0, restored.completedChanges.value)
        restored.uploadSelected()
        advanceUntilIdle()
        assertEquals(gateway.saves.last(), newGateway.saves.single())
        assertEquals(1, restored.completedChanges.value)
    }

    @Test fun restoreCannotOverwriteInputAlreadyEditedInTheCurrentController() = runTest {
        val old = controller()
        old.selectLogos(listOf(selection("old")))
        val current = controller()
        current.selectLogos(listOf(selection("current")))
        val selected = current.state.value.selected
        current.restoreUiState(old.snapshotUiState())
        assertEquals(selected, current.state.value.selected)
    }

    @Test fun confirmedDiscardDoesNotEmitChangesOrUploadAnyData() = runTest {
        val gateway = FakeGateway()
        val controller = controller(gateway)
        controller.selectLogos(listOf(selection("first")))
        controller.discardSelection()
        controller.uploadSelected()
        advanceUntilIdle()
        assertTrue(controller.state.value.selected.isEmpty())
        assertTrue(gateway.saves.isEmpty())
        assertEquals(0, controller.completedChanges.value)
    }

    private class FakeGateway : PartnerBatchGateway {
        val saves = mutableListOf<Pair<Partner, String>>()
        var result: suspend (Partner, String) -> Unit = { _, _ -> }
        override suspend fun save(partner: Partner, requestId: String, progress: (Float) -> Unit) {
            saves += partner to requestId
            progress(.5f)
            result(partner, requestId)
            progress(1f)
        }
    }
}
