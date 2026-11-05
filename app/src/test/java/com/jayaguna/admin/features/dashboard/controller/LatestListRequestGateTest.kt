package com.jayaguna.admin.features.dashboard.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestListRequestGateTest {
    private class ListProbe {
        var busy = false
        var starts = 0
        val values = mutableListOf<String>()
        val errors = mutableListOf<String>()

        fun load(gate: LatestListRequestGate, key: String = "products", reset: Boolean = true, fetch: suspend () -> String): Job? =
            gate.launch(key, reset,
                onStart = { starts++; busy = true; if (reset) values.clear() },
                fetch = fetch,
                onResult = { values += it },
                onError = { errors += it.message.orEmpty() },
                onFinish = { busy = false })
    }

    @Test fun refreshSupersedesAnUncancellableOldPageWithoutClearingNewBusyState() = runTest {
        val gate = LatestListRequestGate(this)
        val probe = ListProbe()
        val oldPage = CompletableDeferred<String>()
        val freshPage = CompletableDeferred<String>()
        probe.load(gate) { withContext(NonCancellable) { oldPage.await() } }
        runCurrent()
        probe.load(gate) { freshPage.await() }
        runCurrent()

        oldPage.complete("stale page")
        runCurrent()
        assertTrue(probe.busy)
        assertTrue(probe.values.isEmpty())
        assertTrue(probe.errors.isEmpty())

        freshPage.complete("fresh page")
        advanceUntilIdle()
        assertEquals(listOf("fresh page"), probe.values)
        assertFalse(probe.busy)
    }

    @Test fun lateOldFailureCannotReplaceTheSuccessfulRefreshWithAnError() = runTest {
        val gate = LatestListRequestGate(this)
        val probe = ListProbe()
        val failOld = CompletableDeferred<Unit>()
        probe.load(gate) {
            withContext(NonCancellable) { failOld.await(); throw IllegalStateException("old error") }
        }
        runCurrent()
        probe.load(gate) { "latest page" }
        runCurrent()
        failOld.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("latest page"), probe.values)
        assertTrue(probe.errors.isEmpty())
        assertFalse(probe.busy)
    }

    @Test fun duplicatePaginationIsSuppressedButNextPageCanStartAfterCompletion() = runTest {
        val gate = LatestListRequestGate(this)
        val probe = ListProbe()
        val page = CompletableDeferred<String>()
        probe.load(gate, reset = false) { page.await() }
        runCurrent()
        val duplicate = probe.load(gate, reset = false) { error("duplicate must not run") }
        assertNull(duplicate)
        assertEquals(1, probe.starts)
        page.complete("page 1")
        advanceUntilIdle()
        probe.load(gate, reset = false) { "page 2" }
        advanceUntilIdle()
        assertEquals(listOf("page 1", "page 2"), probe.values)
        assertFalse(probe.busy)
    }

    @Test fun refreshCancelsPaginationInsteadOfAppendingOldResultsToFreshList() = runTest {
        val gate = LatestListRequestGate(this)
        val probe = ListProbe().apply { values += "old first page" }
        val oldPagination = CompletableDeferred<String>()
        probe.load(gate, reset = false) { withContext(NonCancellable) { oldPagination.await() } }
        runCurrent()
        probe.load(gate) { "new first page" }
        runCurrent()
        oldPagination.complete("old second page")
        advanceUntilIdle()
        assertEquals(listOf("new first page"), probe.values)
    }

    @Test fun refreshingProductsDoesNotCancelThePartnerList() = runTest {
        val gate = LatestListRequestGate(this)
        val products = ListProbe()
        val partners = ListProbe()
        val productPage = CompletableDeferred<String>()
        val partnerPage = CompletableDeferred<String>()
        products.load(gate) { productPage.await() }
        partners.load(gate, key = "partners") { partnerPage.await() }
        runCurrent()
        products.load(gate) { "new products" }
        partnerPage.complete("partners")
        advanceUntilIdle()
        assertEquals(listOf("new products"), products.values)
        assertEquals(listOf("partners"), partners.values)
        assertFalse(products.busy)
        assertFalse(partners.busy)
    }

    @Test fun cancellationClearsBusyAndAllowsAReplacementRequest() = runTest {
        val gate = LatestListRequestGate(this)
        val probe = ListProbe()
        val pending = CompletableDeferred<String>()
        val job = probe.load(gate) { pending.await() }!!
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertFalse(probe.busy)
        assertTrue(probe.errors.isEmpty())
        probe.load(gate, reset = false) { "retry" }
        advanceUntilIdle()
        assertEquals(listOf("retry"), probe.values)
    }

    @Test fun aCancelledScopeDoesNotLeaveAnUnstartedRequestBusy() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        scope.cancel()
        val gate = LatestListRequestGate(scope)
        val probe = ListProbe()
        probe.load(gate) { error("closed scope must not fetch") }
        advanceUntilIdle()
        assertFalse(probe.busy)
        assertTrue(probe.values.isEmpty())
        assertTrue(probe.errors.isEmpty())
    }
}
