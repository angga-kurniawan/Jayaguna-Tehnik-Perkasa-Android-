package com.jayaguna.admin.data.drive

import com.jayaguna.admin.core.security.BackendException

data class DriveAuthorizationSession(val uid: String, val email: String, val generation: Long)

/** Memory only. A result from a previous identity or process cannot restore its token. */
internal class DriveAuthorizationSessionStore(
    initialGeneration: Long = System.nanoTime(),
    private val now: () -> Long,
) {
    private var generation = initialGeneration
    private var session: DriveAuthorizationSession? = null
    private var token: String? = null
    private var validUntil = 0L

    @Synchronized fun session(uid: String, email: String): DriveAuthorizationSession {
        val normalized = email.lowercase(java.util.Locale.ROOT)
        if (session?.uid != uid || session?.email != normalized) {
            reset()
            session = DriveAuthorizationSession(uid, normalized, generation)
        }
        return session!!
    }

    @Synchronized fun requireCurrent(ticket: DriveAuthorizationSession) {
        if (session != ticket) throw BackendException("cancelled", "Sesi akun berubah. Masuk kembali untuk mengizinkan Drive.")
    }

    @Synchronized fun accept(ticket: DriveAuthorizationSession, value: String?, grantedScopes: List<String>) {
        requireCurrent(ticket)
        if (value.isNullOrBlank() || DRIVE_SCOPE !in grantedScopes) {
            throw BackendException("drive-authorization-required", "Izin Google Drive belum diberikan. Izinkan Drive untuk memakai gambar perusahaan.")
        }
        token = value
        // Refresh well before the usual one-hour Google access-token lifetime.
        validUntil = now() + 30 * 60 * 1000L
    }

    @Synchronized fun cached(ticket: DriveAuthorizationSession): String? {
        requireCurrent(ticket)
        return token?.takeIf { now() < validUntil }
    }

    @Synchronized fun invalidate(value: String) {
        if (token == value) { token = null; validUntil = 0 }
    }

    @Synchronized fun reset() {
        generation++
        session = null
        token = null
        validUntil = 0
    }

    companion object { const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive" }
}
