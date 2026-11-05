package com.jayaguna.admin.data.repository

import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.data.model.Account
import org.junit.Assert.*
import org.junit.Test

class NativeAdminRemovalPolicyTest {
    private val owner = Account("owner", "owner@example.com", "Owner", "OWNER")
    private val profile = mapOf<String, Any?>("uid" to "admin", "name" to "Former Admin", "email" to "admin@example.com", "role" to "ADMIN", "status" to "approved")
    private fun allowed(target: Map<String, Any?> = profile, grant: Map<String, Any?> = emptyMap()) =
        NativeAdminRemovalPolicy.requireTarget(owner, "admin", target, grant)

    @Test fun approvedAndPendingDefaultAdminsCanBeRemoved() {
        allowed()
        allowed(profile - "role")
        allowed(profile + ("status" to "pending"))
        allowed(profile + ("active" to false))
        allowed((profile - "status") + ("active" to true))
    }

    @Test fun adminCannotPerformOwnerAction() {
        assertThrows(BackendException::class.java) {
            NativeAdminRemovalPolicy.requireTarget(owner.copy(role = "ADMIN"), "admin", profile, emptyMap())
        }
    }

    @Test fun ownerAndOwnIdentityRemainProtectedIncludingEmailAlias() {
        assertThrows(BackendException::class.java) { allowed(profile + ("role" to "OWNER")) }
        assertThrows(BackendException::class.java) { allowed(grant = mapOf("role" to "OWNER")) }
        assertThrows(BackendException::class.java) { allowed(profile + ("email" to "OWNER@EXAMPLE.COM")) }
        assertThrows(BackendException::class.java) {
            NativeAdminRemovalPolicy.requireTarget(owner, owner.uid, profile, emptyMap())
        }
    }

    @Test fun targetMustBeAnExistingConsistentAdmin() {
        assertThrows(BackendException::class.java) { allowed(emptyMap()) }
        assertThrows(BackendException::class.java) { allowed(profile + ("uid" to "different")) }
        assertThrows(BackendException::class.java) { allowed(profile + ("role" to null)) }
        assertThrows(BackendException::class.java) { allowed(profile + ("status" to true)) }
    }

    @Test fun auditDefaultsMatchApprovedAndLegacyAccessSemantics() {
        val current = NativeAdminRemovalPolicy.snapshot("admin", profile - "role")
        assertEquals("ADMIN", current["role"])
        assertEquals(true, current["active"])
        val legacy = NativeAdminRemovalPolicy.snapshot("admin", (profile - "status") + ("active" to true))
        assertEquals("approved", legacy["status"])
        assertEquals(true, legacy["active"])
        val pending = NativeAdminRemovalPolicy.snapshot("admin", profile + ("status" to "pending"))
        assertEquals(false, pending["active"])
    }

    @Test fun removalSnapshotRetainsIdentityWithoutUnrelatedPrivateFields() {
        val removed = NativeAdminRemovalPolicy.snapshot("admin", profile + mapOf("status" to "revoked", "active" to false, "internal" to "private"))
        assertEquals(setOf("uid", "name", "email", "role", "status", "active"), removed.keys)
        assertEquals("admin@example.com", removed["email"])
        assertEquals("revoked", removed["status"])
        assertEquals(false, removed["active"])
    }

    @Test fun explicitNullNameIsPreservedToMatchFirestoreMapDefaults() {
        val value = NativeAdminRemovalPolicy.snapshot("admin", profile + ("name" to null))
        assertTrue(value.containsKey("name"))
        assertNull(value["name"])
        assertEquals("", NativeAdminRemovalPolicy.snapshot("admin", profile - "name")["name"])
    }

    @Test fun grantLookupUsesOneNormalizedDocumentAndRejectsPaths() {
        assertEquals("admin@example.com", NativeAdminRemovalPolicy.grantEmail(profile + ("email" to "ADMIN@EXAMPLE.COM")))
        assertNull(NativeAdminRemovalPolicy.grantEmail(profile + ("email" to "someone/else")))
        assertNull(NativeAdminRemovalPolicy.grantEmail(profile - "email"))
    }
}
