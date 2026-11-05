package com.jayaguna.admin.features.auth.controller

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.jayaguna.admin.BuildConfig
import com.jayaguna.admin.core.security.firebaseAuthFailureReason
import com.jayaguna.admin.core.security.safeFirebaseAuthErrorCode
import com.jayaguna.admin.data.model.AccessRequestResult
import com.jayaguna.admin.data.model.Account
import com.jayaguna.admin.data.repository.FirebaseRepository
import kotlinx.coroutines.tasks.await

/** Google authentication establishes identity; company approval is checked separately. */
internal interface AuthGateway {
    val signedInUid: String?
    fun observeIdentity(onChange: (String?) -> Unit): () -> Unit
    fun observeAccess(uid: String, onChange: () -> Unit): () -> Unit
    fun requireOnline()
    suspend fun signInWithGoogle(idToken: String)
    suspend fun session(): Account
    suspend fun requestAccess(): AccessRequestResult
    fun signOut()
}

internal class FirebaseAuthGateway(private val repository: FirebaseRepository) : AuthGateway {
    override val signedInUid get() = repository.auth.currentUser?.uid
    override fun observeIdentity(onChange: (String?) -> Unit): () -> Unit {
        val listener = FirebaseAuth.AuthStateListener { onChange(it.currentUser?.uid) }
        repository.auth.addAuthStateListener(listener)
        return { repository.auth.removeAuthStateListener(listener) }
    }
    override fun observeAccess(uid: String, onChange: () -> Unit): () -> Unit = repository.observeAccess(uid, onChange)
    override fun requireOnline() = repository.requireOnline()
    override suspend fun signInWithGoogle(idToken: String) {
        try {
            repository.auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (error: FirebaseAuthException) {
            if (BuildConfig.DEBUG) {
                Log.w("GoogleLogin", "stage=firebase-credential firebaseErrorCode=${safeFirebaseAuthErrorCode(error.errorCode)} reason=${firebaseAuthFailureReason(error.message).diagnosticValue} exceptionClass=${error.javaClass.simpleName}")
            }
            // Keep Firebase's error type so existing session handling remains authoritative.
            throw error
        }
    }
    override suspend fun session() = repository.session()
    override suspend fun requestAccess() = repository.requestAccess()
    override fun signOut() = repository.auth.signOut()
}
