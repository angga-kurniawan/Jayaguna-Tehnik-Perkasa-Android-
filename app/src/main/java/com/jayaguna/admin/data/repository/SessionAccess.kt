package com.jayaguna.admin.data.repository

import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.data.model.Account
import java.util.Locale

/** UID decisions override email grants; only trusted Firestore documents confer access. */
internal object SessionAccess {
    fun isGoogle(claims: Map<String, Any?>): Boolean = claims["email_verified"] == true &&
        (claims["email"] as? String)?.let { it.length in 4..320 && '/' !in it } == true &&
        (claims["firebase"] as? Map<*, *>)?.get("sign_in_provider") == "google.com"

    @Suppress("UNUSED_PARAMETER")
    fun account(uid: String, email: String, displayName: String?, claims: Map<String, Any?>,
                profile: Map<String, Any?>, emailGrant: Map<String, Any?> = emptyMap()): Account {
        val signedEmail = claims["email"] as? String ?: email
        val normalizedEmail = signedEmail.lowercase(Locale.ROOT)
        val rawTime = claims["auth_time"] as? Number
        val authTime = rawTime?.toLong() ?: 0
        if (rawTime != null && (!rawTime.toDouble().isFinite() || rawTime.toDouble() != authTime.toDouble())) denied()
        if (uid.isBlank() || normalizedEmail.isBlank() || !isGoogle(claims) || authTime <= 0) {
            throw BackendException("unauthenticated", "Silakan masuk menggunakan akun Google yang terverifikasi.")
        }
        if (profile.containsKey("active") && profile["active"] != true || profile.containsKey("status") && profile["status"] != "approved") denied()
        val selected = when {
            profile["status"] == "approved" -> profile
            !profile.containsKey("status") && profile["active"] == true &&
                profile["role"] in setOf("OWNER", "ADMIN") && profile["role"] == claims["role"] -> profile
            emailGrant["status"] == "approved" -> emailGrant
            else -> denied()
        }
        val role = if (selected.containsKey("role")) selected["role"] as? String ?: denied() else "ADMIN"
        val revokedBefore = maxOf(cutoff(profile), cutoff(selected))
        if (selected.containsKey("active") && selected["active"] != true || role !in setOf("OWNER", "ADMIN") ||
            selected.containsKey("uid") && selected["uid"] != uid ||
            selected.containsKey("email") && (selected["email"] !is String || (selected["email"] as String).lowercase(Locale.ROOT) != normalizedEmail) ||
            (revokedBefore > 0 && authTime <= revokedBefore)) denied()
        val name = (selected["name"] as? String)?.takeIf(String::isNotBlank)
            ?: (claims["name"] as? String)?.takeIf(String::isNotBlank) ?: signedEmail
        return Account(uid, signedEmail, name, role)
    }

    private fun denied(): Nothing = throw BackendException("permission-denied", "Akun Google belum disetujui atau akses telah dinonaktifkan.")
    private fun cutoff(profile: Map<String, Any?>): Long {
        if (!profile.containsKey("revokedBeforeSeconds")) return 0
        val value = profile["revokedBeforeSeconds"] as? Number ?: denied()
        if (!value.toDouble().isFinite()) denied()
        return value.toLong()
    }
}
