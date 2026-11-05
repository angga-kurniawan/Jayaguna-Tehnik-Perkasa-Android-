package com.jayaguna.admin.data.drive

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import com.jayaguna.admin.core.security.BackendException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

data class DriveAuthorizationAttempt(val session: DriveAuthorizationSession, val pendingIntent: PendingIntent?)

/** Uses the Google account that just authenticated with Firebase, never a stored owner secret. */
class DriveAuthorizationManager(context: Context, private val auth: FirebaseAuth) {
    private val client = Identity.getAuthorizationClient(context.applicationContext)
    private val sessions = DriveAuthorizationSessionStore(now = SystemClock::elapsedRealtime)
    private val mutex = Mutex()
    private val identityListener = FirebaseAuth.AuthStateListener { firebase ->
        val user = firebase.currentUser
        val email = user?.email
        if (user == null || email.isNullOrBlank()) sessions.reset() else sessions.session(user.uid, email)
    }

    init { auth.addAuthStateListener(identityListener) }

    fun currentSession(): DriveAuthorizationSession {
        val user = auth.currentUser
        val email = user?.email
        if (user == null || email.isNullOrBlank() || !user.isEmailVerified || user.providerData.none { it.providerId == "google.com" }) {
            sessions.reset()
            throw BackendException("unauthenticated", "Masuk dengan akun Google untuk mengizinkan Drive.")
        }
        return sessions.session(user.uid, email)
    }

    fun currentEmail(): String = currentSession().email
    fun reset() = sessions.reset()

    /** May return a Google consent dialog for the UI to launch. */
    suspend fun authorize(): DriveAuthorizationAttempt = mutex.withLock {
        val ticket = currentSession()
        if (sessions.cached(ticket) != null) return@withLock DriveAuthorizationAttempt(ticket, null)
        val result = request(ticket)
        requireCurrent(ticket)
        if (result.hasResolution()) {
            val intent = result.pendingIntent ?: throw BackendException("drive-authorization", "Google belum membuka persetujuan Drive. Coba lagi.")
            DriveAuthorizationAttempt(ticket, intent)
        } else {
            accept(ticket, result)
            DriveAuthorizationAttempt(ticket, null)
        }
    }

    suspend fun complete(session: DriveAuthorizationSession, resultCode: Int, data: Intent?) = mutex.withLock {
        requireCurrent(session)
        if (resultCode != Activity.RESULT_OK || data == null) {
            throw BackendException("drive-authorization-required", "Izin Drive belum selesai. Tekan Izinkan Drive untuk melanjutkan pengelolaan gambar.")
        }
        val result = googleResult { client.getAuthorizationResultFromIntent(data) }
        accept(session, result)
    }

    /** Background image operations may refresh an existing grant, but cannot open dialogs. */
    suspend fun accessToken(): String = mutex.withLock {
        val ticket = currentSession()
        sessions.cached(ticket)?.let { return@withLock it }
        val result = request(ticket)
        requireCurrent(ticket)
        if (result.hasResolution()) throw BackendException("drive-authorization-required", "Izin Google Drive diperlukan. Tekan Izinkan Drive untuk melanjutkan.")
        accept(ticket, result)
        sessions.cached(ticket)!!
    }

    suspend fun invalidateToken(token: String) = mutex.withLock {
        sessions.invalidate(token)
        googleResult { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
        Unit
    }

    private suspend fun request(ticket: DriveAuthorizationSession): AuthorizationResult = googleResult {
        client.authorize(AuthorizationRequest.builder()
            .setAccount(Account(ticket.email, "com.google"))
            .setRequestedScopes(listOf(Scope(DriveAuthorizationSessionStore.DRIVE_SCOPE)))
            .build()).await()
    }

    private fun requireCurrent(ticket: DriveAuthorizationSession) {
        if (currentSession() != ticket) throw BackendException("cancelled", "Sesi akun berubah. Silakan coba lagi.")
        sessions.requireCurrent(ticket)
    }

    private fun accept(ticket: DriveAuthorizationSession, result: AuthorizationResult) {
        requireCurrent(ticket)
        sessions.accept(ticket, result.accessToken, result.grantedScopes)
    }

    private suspend fun <T> googleResult(operation: suspend () -> T): T = try { operation() }
    catch (error: CancellationException) { throw error }
    catch (error: ApiException) {
        val detail = when (error.statusCode) {
            7 -> "Google Drive tidak dapat dihubungi. Periksa koneksi internet lalu coba lagi."
            10 -> "Konfigurasi OAuth Drive belum sesuai. Periksa client Android dan sertifikat aplikasi."
            16, 12501 -> "Persetujuan Google Drive dibatalkan. Anda dapat mengizinkannya kembali."
            else -> "Google belum memberikan izin Drive (kode ${error.statusCode}). Periksa akun dan pengaturan OAuth aplikasi."
        }
        throw BackendException("drive-authorization", detail)
    }
}
