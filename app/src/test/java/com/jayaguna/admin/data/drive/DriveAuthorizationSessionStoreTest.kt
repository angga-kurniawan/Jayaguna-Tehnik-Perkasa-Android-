package com.jayaguna.admin.data.drive

import com.jayaguna.admin.core.security.BackendException
import org.junit.Assert.*
import org.junit.Test

class DriveAuthorizationSessionStoreTest {
    private var clock = 1000L
    private val store = DriveAuthorizationSessionStore(10) { clock }
    private val scope = listOf(DriveAuthorizationSessionStore.DRIVE_SCOPE)

    @Test fun returningSameIdentityReusesGrantUntilRefreshTime() {
        val session = store.session("one", "Admin@Example.com")
        store.accept(session, "fixture-token", scope)
        assertEquals(session, store.session("one", "admin@example.com"))
        assertEquals("fixture-token", store.cached(session))
        clock += 30 * 60 * 1000L
        assertNull(store.cached(session))
    }

    @Test fun switchingIdentityRejectsLateConsentAndClearsOldToken() {
        val old = store.session("one", "one@example.com")
        store.accept(old, "old-token", scope)
        val current = store.session("two", "two@example.com")
        assertNull(store.cached(current))
        assertThrows(BackendException::class.java) { store.accept(old, "late-token", scope) }
        assertNull(store.cached(current))
    }

    @Test fun logoutAndSameAccountLoginCannotAcceptPreviousDialog() {
        val old = store.session("one", "one@example.com")
        store.reset()
        val current = store.session("one", "one@example.com")
        assertNotEquals(old, current)
        assertThrows(BackendException::class.java) { store.accept(old, "late-token", scope) }
    }

    @Test fun changedEmailWithSameUidInvalidatesPreviousGrant() {
        val old = store.session("one", "before@example.com")
        store.accept(old, "old-token", scope)
        val next = store.session("one", "after@example.com")
        assertNull(store.cached(next))
        assertThrows(BackendException::class.java) { store.cached(old) }
    }

    @Test fun deniedOrPartialConsentCannotPopulateTokenCache() {
        val session = store.session("one", "one@example.com")
        assertThrows(BackendException::class.java) { store.accept(session, "fixture-token", listOf("openid")) }
        assertThrows(BackendException::class.java) { store.accept(session, "", scope) }
        assertNull(store.cached(session))
    }

    @Test fun expiredTokenInvalidationDoesNotClearReplacementToken() {
        val session = store.session("one", "one@example.com")
        store.accept(session, "new-token", scope)
        store.invalidate("old-token")
        assertEquals("new-token", store.cached(session))
        store.invalidate("new-token")
        assertNull(store.cached(session))
    }

    @Test fun processRestartRejectsRestoredOldPendingTicket() {
        val old = store.session("one", "one@example.com")
        val restarted = DriveAuthorizationSessionStore(100) { clock }
        restarted.session("one", "one@example.com")
        assertThrows(BackendException::class.java) { restarted.accept(old, "fixture-token", scope) }
    }
}
