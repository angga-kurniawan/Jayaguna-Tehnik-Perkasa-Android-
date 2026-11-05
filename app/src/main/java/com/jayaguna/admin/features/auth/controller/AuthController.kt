package com.jayaguna.admin.features.auth.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuthException
import com.jayaguna.admin.core.security.AccessNotApprovedException
import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.core.security.userMessage
import com.jayaguna.admin.data.model.AccessIdentity
import com.jayaguna.admin.data.model.Account
import com.jayaguna.admin.data.repository.FirebaseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class AuthState(
    val loading: Boolean = true,
    val account: Account? = null,
    val identity: AccessIdentity? = null,
    val error: String? = null,
    val notice: String? = null,
    val canRetrySession: Boolean = false,
    val requestSubmitting: Boolean = false
)

/** A Google identity may remain signed in while company access is pending. */
class AuthController internal constructor(private val gateway: AuthGateway) : ViewModel(), ViewModelStoreOwner {
    constructor(repository: FirebaseRepository) : this(FirebaseAuthGateway(repository))

    override val viewModelStore = ViewModelStore()
    private val mutable = MutableStateFlow(AuthState())
    val state = mutable.asStateFlow()
    private var unsubscribeIdentity: (() -> Unit)? = null
    private var unsubscribeAccess: (() -> Unit)? = null
    private var observedUid: String? = null
    private var knownUid = gateway.signedInUid
    private var identityGeneration = 0L
    private var verificationGeneration = 0L
    private var acceptSignedInIdentity = knownUid != null
    private var signingIn = false
    private var signInJob: Job? = null
    private var verificationJob: Job? = null
    private var requestJob: Job? = null
    private var refreshQueued = false

    init {
        unsubscribeIdentity = gateway.observeIdentity(::identityChanged)
        if (gateway.signedInUid != null && acceptSignedInIdentity) verify()
        else mutable.value = AuthState(loading = false)
    }

    private fun stopAccessObservation() {
        unsubscribeAccess?.invoke()
        unsubscribeAccess = null
        observedUid = null
    }

    private fun invalidateIdentity() {
        identityGeneration++
        verificationGeneration++
        signingIn = false
        signInJob?.cancel()
        signInJob = null
        verificationJob?.cancel()
        verificationJob = null
        requestJob?.cancel()
        requestJob = null
        refreshQueued = false
        stopAccessObservation()
        viewModelStore.clear()
    }

    private fun currentIdentity(ticket: Long, uid: String): Boolean =
        ticket == identityGeneration && acceptSignedInIdentity && gateway.signedInUid == uid

    private fun identityChanged(uid: String?) {
        if (uid == null) {
            invalidateIdentity()
            knownUid = null
            acceptSignedInIdentity = false
            mutable.value = AuthState(loading = false)
            return
        }
        if (!acceptSignedInIdentity) {
            // A Firebase task may finish after logout even when its coroutine was cancelled.
            gateway.signOut()
            return
        }
        if (uid == knownUid) return
        if (signingIn) {
            knownUid = uid
            viewModelStore.clear()
            stopAccessObservation()
        } else {
            invalidateIdentity()
            knownUid = uid
            mutable.value = AuthState()
            verify()
        }
    }

    private fun observeAccess(uid: String, ticket: Long) {
        if (observedUid == uid || !currentIdentity(ticket, uid)) return
        stopAccessObservation()
        observedUid = uid
        val unsubscribe = gateway.observeAccess(uid) accessChanged@{
            if (!currentIdentity(ticket, uid) || signingIn) return@accessChanged
            // Remove privileged screens immediately; only a fresh server check restores them.
            viewModelStore.clear()
            mutable.value = mutable.value.copy(account = null)
            if (verificationJob?.isActive == true) refreshQueued = true else verify()
        }
        if (currentIdentity(ticket, uid) && observedUid == uid) unsubscribeAccess = unsubscribe
        else unsubscribe()
    }

    private fun verify() {
        val uid = gateway.signedInUid
        if (uid == null) {
            mutable.value = AuthState(loading = false, error = "Masuk dengan akun Google untuk melanjutkan.")
            return
        }
        verificationGeneration++
        val revision = verificationGeneration
        val ticket = identityGeneration
        verificationJob?.cancel()
        refreshQueued = false
        viewModelStore.clear()
        mutable.value = mutable.value.copy(loading = true, account = null, error = null)
        verificationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // Start watching before the server read so changes during verification are queued.
                observeAccess(uid, ticket)
                gateway.requireOnline()
                val account = gateway.session()
                if (!currentIdentity(ticket, uid) || revision != verificationGeneration) return@launch
                if (account.uid != uid) throw BackendException("unauthenticated", "Identitas sesi berubah. Masuk dengan Google kembali.")
                if (refreshQueued) return@launch
                requestJob?.cancel()
                requestJob = null
                mutable.value = AuthState(loading = false, account = account)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentIdentity(ticket, uid) && revision == verificationGeneration) handleSessionFailure(error, uid)
            } finally {
                if (currentIdentity(ticket, uid) && revision == verificationGeneration) {
                    verificationJob = null
                    if (refreshQueued) {
                        refreshQueued = false
                        verify()
                    }
                }
            }
        }
        verificationJob?.start()
    }

    private fun handleSessionFailure(error: Exception, uid: String) {
        when {
            error is AccessNotApprovedException -> {
                if (error.identity.uid != uid) {
                    discardSession("Identitas akses tidak cocok. Masuk dengan Google kembali.")
                    return
                }
                mutable.value = mutable.value.copy(
                    loading = false, account = null, identity = error.identity,
                    error = null, canRetrySession = true
                )
            }
            terminal(error) -> discardSession(error.userMessage())
            else -> mutable.value = mutable.value.copy(
                loading = false, account = null, error = error.userMessage(), canRetrySession = true
            )
        }
    }

    private fun terminal(error: Exception): Boolean = when (error) {
        is BackendException -> error.code in setOf("unauthenticated", "permission-denied")
        is FirebaseAuthException -> error.errorCode in setOf(
            "ERROR_USER_DISABLED", "ERROR_USER_NOT_FOUND", "ERROR_USER_TOKEN_EXPIRED",
            "ERROR_INVALID_USER_TOKEN", "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS"
        )
        else -> false
    }

    private fun discardSession(message: String) {
        invalidateIdentity()
        knownUid = null
        acceptSignedInIdentity = false
        gateway.signOut()
        mutable.value = AuthState(loading = false, error = message)
    }

    fun loginWithGoogle(idToken: String) {
        if (mutable.value.loading || mutable.value.requestSubmitting) return
        if (idToken.isBlank()) {
            googleSignInFailed(BackendException("invalid-credential", "Google belum memberikan identitas akun. Coba lagi."))
            return
        }
        invalidateIdentity()
        val ticket = identityGeneration
        acceptSignedInIdentity = true
        signingIn = true
        mutable.value = AuthState()
        signInJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                gateway.requireOnline()
                gateway.signInWithGoogle(idToken)
                if (ticket == identityGeneration && acceptSignedInIdentity) {
                    knownUid = gateway.signedInUid
                    signingIn = false
                    verify()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (ticket == identityGeneration) {
                    signingIn = false
                    val uid = gateway.signedInUid
                    if (uid != null && acceptSignedInIdentity) handleSessionFailure(error, uid)
                    else {
                        acceptSignedInIdentity = false
                        mutable.value = AuthState(loading = false, error = error.userMessage())
                    }
                }
            } finally {
                if (ticket == identityGeneration) signingIn = false
            }
        }
        signInJob?.start()
    }

    fun googleSignInFailed(error: Throwable) {
        if (error is CancellationException) return
        val cancelled = error is BackendException && error.code == "cancelled"
        mutable.value = mutable.value.copy(
            loading = false,
            error = if (cancelled) null else error.userMessage(),
            notice = if (cancelled) "Pemilihan akun Google dibatalkan." else null
        )
    }

    fun retrySession() {
        if (mutable.value.loading || mutable.value.requestSubmitting || signingIn) return
        if (gateway.signedInUid == null) mutable.value = AuthState(loading = false)
        else verify()
    }

    fun requestAccess() {
        val identity = mutable.value.identity ?: return
        if (mutable.value.loading || mutable.value.requestSubmitting || identity.requestPending || mutable.value.account != null) return
        if (identity.status.lowercase(Locale.ROOT) in setOf("rejected", "denied", "revoked", "inactive", "disabled")) return
        val uid = gateway.signedInUid ?: return
        if (identity.uid != uid) return
        val ticket = identityGeneration
        mutable.value = mutable.value.copy(requestSubmitting = true, error = null, notice = null)
        requestJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                gateway.requireOnline()
                val result = gateway.requestAccess()
                if (!currentIdentity(ticket, uid) || mutable.value.account != null) return@launch
                mutable.value = mutable.value.copy(
                    identity = identity.copy(status = "pending", requestPending = true),
                    requestSubmitting = false, notice = result.message, error = null
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentIdentity(ticket, uid) && mutable.value.account == null) {
                    mutable.value = mutable.value.copy(requestSubmitting = false, error = error.userMessage())
                }
            } finally {
                if (currentIdentity(ticket, uid)) {
                    requestJob = null
                    mutable.value = mutable.value.copy(requestSubmitting = false)
                }
            }
        }
        requestJob?.start()
    }

    fun logout() {
        invalidateIdentity()
        knownUid = null
        acceptSignedInIdentity = false
        gateway.signOut()
        mutable.value = AuthState(loading = false)
    }

    override fun onCleared() {
        invalidateIdentity()
        unsubscribeIdentity?.invoke()
        unsubscribeIdentity = null
    }
}
