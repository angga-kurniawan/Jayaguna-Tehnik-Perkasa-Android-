package com.jayaguna.admin.data.repository

import com.jayaguna.admin.core.security.BackendException
import org.junit.Assert.*
import org.junit.Test

class SessionAccessTest {
    private val email = "user@example.com"
    private fun google(extra: Map<String, Any?> = emptyMap()) = mapOf<String, Any?>(
        "email" to email, "email_verified" to true, "firebase" to mapOf("sign_in_provider" to "google.com"),
        "auth_time" to 200L, "name" to "Google Name") + extra
    private fun account(profile: Map<String, Any?> = emptyMap(), grant: Map<String, Any?> = emptyMap(), claims: Map<String, Any?> = google()) =
        SessionAccess.account("uid", email, "Different SDK display name", claims, profile, grant)
    private fun denied(profile: Map<String, Any?> = emptyMap(), grant: Map<String, Any?> = emptyMap(), claims: Map<String, Any?> = google()) {
        assertThrows(BackendException::class.java) { account(profile, grant, claims) }
    }

    @Test fun consoleApprovalWorksWithoutCustomClaimsOrActiveField() {
        val approved = account(mapOf("status" to "approved"))
        assertEquals("ADMIN", approved.role)
        assertEquals("Google Name", approved.name)
        assertEquals("OWNER", account(mapOf("status" to "approved", "role" to "OWNER")).role)
    }
    @Test fun googleIdentityAloneNeverApprovesAUser() {
        denied()
        denied(claims = google(mapOf("role" to "OWNER")))
        denied(profile = mapOf("name" to "Self", "role" to "OWNER", "active" to true))
    }
    @Test fun onlyVerifiedGoogleSignInCanUseApproval() {
        val approved = mapOf("status" to "approved")
        denied(approved, claims = google(mapOf("email_verified" to false)))
        denied(approved, claims = google(mapOf("firebase" to mapOf("sign_in_provider" to "password"))))
        denied(approved, claims = google() - "firebase")
    }
    @Test fun developerCanPreapproveByVerifiedEmail() {
        val approved = account(grant = mapOf("status" to "approved", "email" to "USER@example.com", "name" to "Trusted name"))
        assertEquals("ADMIN", approved.role)
        assertEquals("Trusted name", approved.name)
    }
    @Test fun explicitUidDecisionsOverrideEmailAndLegacyClaims() {
        val grant = mapOf("status" to "approved", "role" to "OWNER")
        for (status in listOf("pending", "rejected", "revoked", "disabled")) {
            denied(mapOf("status" to status, "role" to "OWNER", "active" to true), grant, google(mapOf("role" to "OWNER")))
        }
        denied(mapOf("active" to false), grant)
        assertEquals("ADMIN", account(mapOf("status" to "approved", "role" to "ADMIN"), grant).role)
    }
    @Test fun existingGrantedUidKeepsItsRoleDuringGoogleMigration() {
        for (role in listOf("OWNER", "ADMIN")) {
            assertEquals(role, account(mapOf("role" to role, "active" to true),
                mapOf("status" to "approved", "role" to "ADMIN"), google(mapOf("role" to role))).role)
        }
    }
    @Test fun revocationFencesUidAndEmailGrantWithoutChangingClaims() {
        val grant = mapOf("status" to "approved", "revokedBeforeSeconds" to 200L)
        denied(grant = grant)
        assertEquals("ADMIN", account(grant = grant, claims = google(mapOf("auth_time" to 201L))).role)
        denied(mapOf("revokedBeforeSeconds" to 200L), mapOf("status" to "approved"))
    }
    @Test fun malformedOrMismatchedApprovalsAreDenied() {
        for (bad in listOf(
            mapOf("uid" to "another-user"), mapOf("uid" to null), mapOf("email" to "other@example.com"),
            mapOf("email" to null), mapOf("role" to "USER"), mapOf("role" to null),
            mapOf("active" to "true"), mapOf("active" to null), mapOf("active" to false)
        )) denied(mapOf("status" to "approved") + bad)
    }
    @Test fun uidCutoffAndAuthTimeRemainRequired() {
        denied(mapOf("status" to "approved"), claims = google() - "auth_time")
        denied(mapOf("status" to "approved"), claims = google(mapOf("auth_time" to 0L)))
        assertThrows(BackendException::class.java) { SessionAccess.account("", email, null, google(), mapOf("status" to "approved")) }
    }
    @Test fun auditNameUsesTrustedProfileOrTokenRatherThanLocalDisplayName() {
        assertEquals("Trusted", account(mapOf("status" to "approved", "name" to "Trusted")).name)
        assertEquals("Google Name", account(mapOf("status" to "approved", "name" to "")).name)
        assertEquals(email, account(mapOf("status" to "approved"), claims = google() - "name").name)
    }
    @Test fun mixedCaseEmailUsesNormalizedGrantButPreservesSignedAuditIdentity() {
        val signed = "User@Example.com"
        val value = account(grant = mapOf("status" to "approved", "email" to "user@example.com"), claims = google(mapOf("email" to signed)) - "name")
        assertEquals(signed, value.email)
        assertEquals(signed, value.name)
    }
    @Test fun malformedRevocationAndFractionalAuthenticationTimesFailClosed() {
        for (value in listOf(null, "200", Double.NaN, Double.POSITIVE_INFINITY)) {
            denied(mapOf("status" to "approved", "revokedBeforeSeconds" to value))
            denied(mapOf("revokedBeforeSeconds" to value), mapOf("status" to "approved"))
        }
        denied(mapOf("status" to "approved"), claims = google(mapOf("auth_time" to 200.5)))
    }
}
