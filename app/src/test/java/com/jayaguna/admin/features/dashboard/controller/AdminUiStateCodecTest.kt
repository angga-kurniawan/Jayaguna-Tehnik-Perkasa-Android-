package com.jayaguna.admin.features.dashboard.controller

import com.jayaguna.admin.data.model.ActivityFilter
import com.jayaguna.admin.data.repository.ProductFilter
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AdminUiStateCodecTest {
    @Test fun onlyAppliedFiltersRoundTripWithoutServerOrAuthenticationData() {
        val product = ProductFilter("daikin 1 pk", "split", "active")
        val activity = ActivityFilter("2026-10-01", "2026-10-29", "admin-id", "portfolioCorporate", "edit")
        val snapshot = AdminUiStateCodec.encode(product, activity)
        assertEquals(AdminUiSnapshot(product, activity), AdminUiStateCodec.decode(snapshot))
        assertEquals(11, snapshot.size)
    }

    @Test fun unknownOrOversizedSnapshotsAreIgnored() {
        val valid = AdminUiStateCodec.encode(ProductFilter(), ActivityFilter())
        assertNull(AdminUiStateCodec.decode(valid.dropLast(1)))
        assertNull(AdminUiStateCodec.decode(valid.toMutableList().apply { this[0] = "unknown" }))
        assertNull(AdminUiStateCodec.decode(valid.toMutableList().apply { this[1] = "x".repeat(1001) }))
    }

    @Test fun stablePendingRequestIsRestoredButPartialOrMalformedKeysAreRejected() {
        val request = UUID.randomUUID().toString()
        val snapshot = AdminUiStateCodec.encode(ProductFilter("daikin"), ActivityFilter(), "a".repeat(64), request)
        val restored = requireNotNull(AdminUiStateCodec.decode(snapshot))
        assertEquals("a".repeat(64), restored.pendingKeyHash)
        assertEquals(request, restored.pendingRequest)
        assertNull(AdminUiStateCodec.decode(snapshot.toMutableList().apply { this[10] = "" }))
        assertNull(AdminUiStateCodec.decode(snapshot.toMutableList().apply { this[9] = "password" }))
        val legacy = snapshot.take(9).toMutableList().apply { this[0] = "1" }
        assertEquals(AdminUiSnapshot(ProductFilter("daikin"), ActivityFilter()), AdminUiStateCodec.decode(legacy))
    }
}
