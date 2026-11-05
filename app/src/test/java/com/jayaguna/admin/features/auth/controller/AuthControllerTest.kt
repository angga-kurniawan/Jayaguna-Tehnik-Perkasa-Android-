package com.jayaguna.admin.features.auth.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.jayaguna.admin.core.security.AccessNotApprovedException
import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.data.model.AccessIdentity
import com.jayaguna.admin.data.model.AccessRequestResult
import com.jayaguna.admin.data.model.Account
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthControllerTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val owner = Account("owner-uid", "owner@example.com", "Owner", "OWNER")
    private val applicant = AccessIdentity("applicant-uid", "Applicant", "applicant@example.com", "unapproved", false)

    @Before fun configureMainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        stores.clear()
        Dispatchers.resetMain()
    }

    private fun controller(gateway: FakeGateway): AuthController = AuthController(gateway).also { controller ->
        stores += ViewModelStore().apply { put("auth", controller) }
    }

    @Test fun googlePickerCancellationKeepsLoginAvailableWithoutSigningIn() = runTest {
        val gateway = FakeGateway(null)
        val controller = controller(gateway)
        controller.googleSignInFailed(BackendException("cancelled", "Cancelled"))
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.error)
        assertNotNull(controller.state.value.notice)
        assertNull(controller.state.value.account)
        assertEquals(0, gateway.signInCalls)
        controller.loginWithGoogle("fixture-google-token")
        advanceUntilIdle()
        assertEquals(owner, controller.state.value.account)
    }

    @Test fun blankGoogleTokenIsRejectedBeforeFirebaseSignIn() = runTest {
        val gateway = FakeGateway(null)
        val controller = controller(gateway)
        controller.loginWithGoogle(" ")
        assertEquals(0, gateway.signInCalls)
        assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.loading)
    }

    @Test fun googleIdentityWithoutApprovalRemainsSignedInAndCannotOpenCatalog() = runTest {
        val gateway = FakeGateway(null).apply {
            signInUid = applicant.uid
            sessionResult = { throw AccessNotApprovedException(applicant) }
        }
        val controller = controller(gateway)
        controller.loginWithGoogle("fixture-google-token")
        advanceUntilIdle()
        assertEquals(applicant.uid, gateway.signedInUid)
        assertEquals(applicant, controller.state.value.identity)
        assertNull(controller.state.value.account)
        assertTrue(controller.state.value.canRetrySession)
        assertEquals(0, gateway.signOutCalls)
    }

    @Test fun savedGoogleIdentitySurvivesOfflineVerificationAndRetryRecovers() = runTest {
        val gateway = FakeGateway(owner.uid).apply { sessionResult = { throw IOException("offline") } }
        val controller = controller(gateway)
        advanceUntilIdle()
        assertNull(controller.state.value.account)
        assertTrue(controller.state.value.canRetrySession)
        assertEquals(owner.uid, gateway.signedInUid)
        assertEquals(0, gateway.signOutCalls)
        gateway.sessionResult = { owner }
        controller.retrySession()
        advanceUntilIdle()
        assertEquals(owner, controller.state.value.account)
        assertEquals(0, gateway.signInCalls)
    }

    @Test fun temporaryFailureAfterGoogleExchangeRetriesWithoutAnotherGoogleToken() = runTest {
        val gateway = FakeGateway(null).apply { sessionResult = { throw IOException("offline") } }
        val controller = controller(gateway)
        controller.loginWithGoogle("fixture-google-token")
        advanceUntilIdle()
        assertTrue(controller.state.value.canRetrySession)
        assertNull(controller.state.value.account)
        gateway.sessionResult = { owner }
        controller.retrySession()
        advanceUntilIdle()
        assertEquals(owner, controller.state.value.account)
        assertEquals(1, gateway.signInCalls)
    }

    @Test fun accessRequestIsSingleFlightAndSuccessDoesNotGrantAccess() = runTest {
        val receipt = CompletableDeferred<AccessRequestResult>()
        val gateway = FakeGateway(applicant.uid).apply {
            sessionResult = { throw AccessNotApprovedException(applicant) }
            requestResult = { receipt.await() }
        }
        val controller = controller(gateway)
        advanceUntilIdle()
        controller.requestAccess()
        controller.requestAccess()
        runCurrent()
        assertTrue(controller.state.value.requestSubmitting)
        assertEquals(1, gateway.requestCalls)
        receipt.complete(AccessRequestResult("Permohonan tersimpan."))
        advanceUntilIdle()
        assertTrue(controller.state.value.identity!!.requestPending)
        assertFalse(controller.state.value.requestSubmitting)
        assertNull(controller.state.value.account)
        controller.requestAccess()
        advanceUntilIdle()
        assertEquals(1, gateway.requestCalls)
    }

    @Test fun alreadyPendingServerStatusCannotSendAnotherRequest() = runTest {
        val pending = applicant.copy(status = "pending", requestPending = true)
        val gateway = FakeGateway(applicant.uid).apply { sessionResult = { throw AccessNotApprovedException(pending) } }
        val controller = controller(gateway)
        advanceUntilIdle()
        controller.requestAccess()
        advanceUntilIdle()
        assertEquals(0, gateway.requestCalls)
        assertNull(controller.state.value.account)
    }

    @Test fun failedRequestKeepsGoogleIdentityAndCanRetrySafely() = runTest {
        val gateway = FakeGateway(applicant.uid).apply {
            sessionResult = { throw AccessNotApprovedException(applicant) }
            requestResult = { throw IOException("offline") }
        }
        val controller = controller(gateway)
        advanceUntilIdle()
        controller.requestAccess()
        advanceUntilIdle()
        assertEquals(applicant, controller.state.value.identity)
        assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.requestSubmitting)
        assertEquals(applicant.uid, gateway.signedInUid)
        gateway.requestResult = { AccessRequestResult("Permohonan sudah tersimpan.", alreadyPending = true) }
        controller.requestAccess()
        advanceUntilIdle()
        assertEquals(2, gateway.requestCalls)
        assertTrue(controller.state.value.identity!!.requestPending)
        assertNull(controller.state.value.account)
    }

    @Test fun reviewedOrRevokedAccountCannotSubmitAnotherAccessRequest() = runTest {
        for (status in listOf("rejected", "revoked", "disabled")) {
            val gateway = FakeGateway(applicant.uid).apply {
                sessionResult = { throw AccessNotApprovedException(applicant.copy(status = status)) }
            }
            val controller = controller(gateway)
            advanceUntilIdle()
            controller.requestAccess()
            advanceUntilIdle()
            assertEquals(0, gateway.requestCalls)
            assertEquals(applicant.uid, gateway.signedInUid)
        }
    }

    @Test fun ownerApprovalAutomaticallyRechecksServerAndOpensApprovedAccount() = runTest {
        val approved = owner.copy(uid = applicant.uid, email = applicant.email, role = "ADMIN")
        val gateway = FakeGateway(applicant.uid).apply { sessionResult = { throw AccessNotApprovedException(applicant.copy(requestPending = true)) } }
        val controller = controller(gateway)
        advanceUntilIdle()
        gateway.sessionResult = { approved }
        gateway.accessListener!!.invoke()
        advanceUntilIdle()
        assertEquals(approved, controller.state.value.account)
        assertNull(controller.state.value.identity)
        assertEquals(0, gateway.signInCalls)
        assertEquals(0, gateway.signOutCalls)
    }

    @Test fun revokedAccessClearsPrivilegedViewModelsAndKeepsGoogleIdentityPending() = runTest {
        val gateway = FakeGateway(owner.uid)
        val controller = controller(gateway)
        advanceUntilIdle()
        var cleared = false
        controller.viewModelStore.put("private", object : ViewModel() { override fun onCleared() { cleared = true } })
        val revoked = AccessIdentity(owner.uid, owner.name, owner.email, "revoked", false)
        gateway.sessionResult = { throw AccessNotApprovedException(revoked) }
        gateway.accessListener!!.invoke()
        assertTrue(cleared)
        assertNull(controller.state.value.account)
        advanceUntilIdle()
        assertEquals(revoked, controller.state.value.identity)
        assertEquals(owner.uid, gateway.signedInUid)
        assertEquals(0, gateway.signOutCalls)
    }

    @Test fun initialAccessSnapshotDoesNotCreateAnObserverResubscriptionLoop() = runTest {
        val gateway = FakeGateway(owner.uid).apply { emitInitialAccessSnapshot = true }
        val controller = controller(gateway)
        advanceUntilIdle()
        assertEquals(owner, controller.state.value.account)
        assertEquals(1, gateway.accessSubscriptions)
        assertEquals(2, gateway.sessionCalls)
    }

    @Test fun accessChangeDuringServerReadNeverPublishesTheOutdatedApproval() = runTest {
        val oldApproval = CompletableDeferred<Account>()
        val revoked = AccessIdentity(owner.uid, owner.name, owner.email, "revoked", false)
        var reads = 0
        val gateway = FakeGateway(owner.uid).apply {
            sessionResult = {
                if (++reads == 1) withContext(NonCancellable) { oldApproval.await() }
                else throw AccessNotApprovedException(revoked)
            }
        }
        val controller = controller(gateway)
        val observedAccounts = mutableListOf<Account?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.collect { observedAccounts += it.account }
        }
        runCurrent()
        gateway.accessListener!!.invoke()
        oldApproval.complete(owner)
        advanceUntilIdle()
        assertTrue(observedAccounts.all { it == null })
        assertEquals(revoked, controller.state.value.identity)
        assertEquals(2, reads)
    }

    @Test fun requestReceiptAfterLiveApprovalCannotRestorePendingScreen() = runTest {
        val receipt = CompletableDeferred<AccessRequestResult>()
        val approved = owner.copy(uid = applicant.uid, email = applicant.email, role = "ADMIN")
        val gateway = FakeGateway(applicant.uid).apply {
            sessionResult = { throw AccessNotApprovedException(applicant) }
            requestResult = { withContext(NonCancellable) { receipt.await() } }
        }
        val controller = controller(gateway)
        advanceUntilIdle()
        controller.requestAccess()
        runCurrent()
        gateway.sessionResult = { approved }
        gateway.accessListener!!.invoke()
        runCurrent()
        assertEquals(approved, controller.state.value.account)
        receipt.complete(AccessRequestResult("Permohonan tersimpan."))
        advanceUntilIdle()
        assertEquals(approved, controller.state.value.account)
        assertNull(controller.state.value.identity)
        assertFalse(controller.state.value.requestSubmitting)
    }

    @Test fun logoutPreventsLateServerVerificationFromRestoringAccess() = runTest {
        val pending = CompletableDeferred<Account>()
        val gateway = FakeGateway(owner.uid).apply { sessionResult = { withContext(NonCancellable) { pending.await() } } }
        val controller = controller(gateway)
        runCurrent()
        controller.logout()
        pending.complete(owner)
        advanceUntilIdle()
        assertNull(controller.state.value.account)
        assertNull(controller.state.value.identity)
        assertNull(gateway.signedInUid)
    }

    @Test fun lateFirebaseGoogleSignInAfterLogoutIsDiscarded() = runTest {
        val exchange = CompletableDeferred<Unit>()
        val gateway = FakeGateway(null).apply {
            signInResult = { withContext(NonCancellable) { exchange.await(); emitIdentity(owner.uid) } }
        }
        val controller = controller(gateway)
        controller.loginWithGoogle("fixture-google-token")
        runCurrent()
        controller.logout()
        exchange.complete(Unit)
        advanceUntilIdle()
        assertNull(gateway.signedInUid)
        assertNull(controller.state.value.account)
        assertNull(controller.state.value.identity)
    }

    @Test fun identitySwitchFencesOldApprovalAndRechecksNewGoogleIdentity() = runTest {
        val oldApproval = CompletableDeferred<Account>()
        val gateway = FakeGateway(owner.uid).apply {
            sessionResult = {
                if (signedInUid == owner.uid) withContext(NonCancellable) { oldApproval.await() }
                else throw AccessNotApprovedException(applicant)
            }
        }
        val controller = controller(gateway)
        runCurrent()
        gateway.emitIdentity(applicant.uid)
        runCurrent()
        oldApproval.complete(owner)
        advanceUntilIdle()
        assertNull(controller.state.value.account)
        assertEquals(applicant, controller.state.value.identity)
        assertEquals(applicant.uid, gateway.signedInUid)
    }

    @Test fun requestFinishingAfterLogoutCannotRestorePendingIdentity() = runTest {
        val receipt = CompletableDeferred<AccessRequestResult>()
        val gateway = FakeGateway(applicant.uid).apply {
            sessionResult = { throw AccessNotApprovedException(applicant) }
            requestResult = { withContext(NonCancellable) { receipt.await() } }
        }
        val controller = controller(gateway)
        advanceUntilIdle()
        controller.requestAccess()
        runCurrent()
        controller.logout()
        receipt.complete(AccessRequestResult("Permohonan tersimpan."))
        advanceUntilIdle()
        assertNull(controller.state.value.account)
        assertNull(controller.state.value.identity)
        assertFalse(controller.state.value.requestSubmitting)
        assertNull(controller.state.value.notice)
    }

    @Test fun oldAccessListenerCannotRefreshAnotherUserAfterLogout() = runTest {
        val gateway = FakeGateway(owner.uid)
        val controller = controller(gateway)
        advanceUntilIdle()
        val oldListener = gateway.accessListener!!
        controller.logout()
        val calls = gateway.sessionCalls
        oldListener()
        advanceUntilIdle()
        assertEquals(calls, gateway.sessionCalls)
        assertNull(controller.state.value.account)
    }

    @Test fun serverAccountForDifferentUidIsRejectedAndSignedOut() = runTest {
        val gateway = FakeGateway(owner.uid).apply { sessionResult = { owner.copy(uid = "different-uid") } }
        val controller = controller(gateway)
        advanceUntilIdle()
        assertNull(controller.state.value.account)
        assertNull(gateway.signedInUid)
        assertEquals(1, gateway.signOutCalls)
    }

    private inner class FakeGateway(initialUid: String?) : AuthGateway {
        private var uid = initialUid
        private var identityListener: ((String?) -> Unit)? = null
        var accessListener: (() -> Unit)? = null
        var signInCalls = 0
        var signOutCalls = 0
        var sessionCalls = 0
        var requestCalls = 0
        var accessSubscriptions = 0
        var emitInitialAccessSnapshot = false
        var signInUid = owner.uid
        var sessionResult: suspend () -> Account = { owner }
        var requestResult: suspend () -> AccessRequestResult = { AccessRequestResult("Permohonan tersimpan.") }
        var signInResult: suspend () -> Unit = { emitIdentity(signInUid) }
        override val signedInUid get() = uid
        override fun observeIdentity(onChange: (String?) -> Unit): () -> Unit {
            identityListener = onChange
            onChange(uid)
            return { identityListener = null }
        }
        override fun observeAccess(uid: String, onChange: () -> Unit): () -> Unit {
            accessSubscriptions++
            accessListener = onChange
            if (emitInitialAccessSnapshot) onChange()
            return { if (accessListener === onChange) accessListener = null }
        }
        override fun requireOnline() = Unit
        override suspend fun signInWithGoogle(idToken: String) { signInCalls++; signInResult() }
        override suspend fun session(): Account { sessionCalls++; return sessionResult() }
        override suspend fun requestAccess(): AccessRequestResult { requestCalls++; return requestResult() }
        override fun signOut() { signOutCalls++; emitIdentity(null) }
        fun emitIdentity(value: String?) { uid = value; identityListener?.invoke(value) }
    }
}
