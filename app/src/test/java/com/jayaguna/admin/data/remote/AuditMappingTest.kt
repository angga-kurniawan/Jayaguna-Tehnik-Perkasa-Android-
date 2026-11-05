package com.jayaguna.admin.data.remote

import org.junit.Assert.*
import org.junit.Test

class AuditMappingTest {
    @Test fun displayUsesAuthoritativeSnapshotsInsteadOfClientDisplayIndex() {
        val log = mapOf<String, Any>(
            "before" to mapOf("name" to "Daikin lama", "status" to "draft"),
            "after" to mapOf("name" to "Daikin baru", "status" to "draft"),
            "changedFields" to mapOf("name" to mapOf("before" to "palsu", "after" to "palsu"), "role" to mapOf("after" to "OWNER"))
        ).audit("entry")
        assertEquals(setOf("name"), log.changedFields.keys)
        assertEquals("Daikin lama", log.changedFields.getValue("name").before)
        assertEquals("Daikin baru", log.changedFields.getValue("name").after)
    }
}
