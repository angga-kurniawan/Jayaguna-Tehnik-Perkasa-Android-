package com.jayaguna.admin.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FirebaseDashboardTest {
    @Test fun dashboardScanIncludesEveryDocumentBeyondTheFirstHundred() = runTest {
        val rows = (0 until 257).toList()
        val consumed = mutableListOf<Int>()
        val cursors = mutableListOf<Int?>()
        scanDashboardPages<Int>(fetch = { cursor, limit ->
            assertEquals(100, limit)
            cursors += cursor
            rows.drop((cursor ?: -1) + 1).take(limit)
        }, consume = { consumed += it })
        assertEquals(rows, consumed)
        assertEquals(listOf(null, 99, 199), cursors)
    }

    @Test fun exactHundredMultiplesFinishAfterAnEmptyLastPageWithoutDuplicates() = runTest {
        val consumed = mutableListOf<Int>()
        var requests = 0
        scanDashboardPages<Int>(fetch = { cursor, limit ->
            requests++
            (0 until 200).drop((cursor ?: -1) + 1).take(limit)
        }, consume = { consumed += it })
        assertEquals(3, requests)
        assertEquals((0 until 200).toList(), consumed)
    }

    @Test fun emptyCollectionsHaveNoItemsAndDoNotLoop() = runTest {
        var requests = 0
        scanDashboardPages<Int>(fetch = { cursor, limit ->
            requests++
            assertNull(cursor)
            assertEquals(100, limit)
            emptyList()
        }, consume = { fail("An empty collection must not consume a document") })
        assertEquals(1, requests)
    }

    @Test fun aLaterPageFailurePropagatesRatherThanReturningAnIncompleteCount() = runTest {
        val failure = IllegalStateException("test-only page failure")
        var consumed = 0
        val error = runCatching {
            scanDashboardPages<Int>(fetch = { cursor, _ ->
                if (cursor != null) throw failure
                (0 until 100).toList()
            }, consume = { consumed++ })
        }.exceptionOrNull()
        assertSame(failure, error)
        assertEquals(100, consumed)
    }

    @Test fun cancelledReadsStopBeforeConsumingOrRequestingAnotherPage() = runTest {
        var requests = 0
        var consumed = 0
        var error: Throwable? = null
        val child = launch {
            error = runCatching {
                scanDashboardPages<Int>(fetch = { _, _ ->
                    requests++
                    currentCoroutineContext().cancel()
                    (0 until 100).toList()
                }, consume = { consumed++ })
            }.exceptionOrNull()
        }
        child.join()
        assertTrue(error is CancellationException)
        assertEquals(1, requests)
        assertEquals(0, consumed)
    }
}
