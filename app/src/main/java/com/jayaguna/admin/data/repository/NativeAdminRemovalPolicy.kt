package com.jayaguna.admin.data.repository

import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.data.model.Account
import java.util.Locale

/** Removal is a durable access decision; deleting the profile would lose its approval veto. */
internal object NativeAdminRemovalPolicy {
    fun requireTarget(owner: Account, uid: String, profile: Map<String, Any?>, grant: Map<String, Any?>) {
        if (owner.role != "OWNER" || uid == owner.uid || profile.isEmpty() ||
            profile.getOrDefault("role", "ADMIN") != "ADMIN" || grant["role"] == "OWNER" ||
            profile.containsKey("uid") && profile["uid"] != uid ||
            (profile["email"] as? String)?.equals(owner.email, ignoreCase = true) == true) {
            throw BackendException("permission-denied", "Hanya OWNER yang dapat menghapus akses admin lain. Akun OWNER dilindungi.")
        }
        if (profile.containsKey("status") && profile["status"] !is String) {
            throw BackendException("failed-precondition", "Status akun admin tidak valid. Hubungi pengelola aplikasi.")
        }
    }

    fun snapshot(uid: String, profile: Map<String, Any?>): Map<String, Any?> = mapOf(
        "uid" to uid, "name" to profile.getOrDefault("name", ""), "email" to profile.getOrDefault("email", ""),
        "role" to profile.getOrDefault("role", "ADMIN"),
        "status" to profile.getOrDefault("status", if (profile["active"] == true) "approved" else "inactive"),
        "active" to (if (profile.containsKey("active")) profile["active"] == true else profile["status"] == "approved"),
    )

    fun grantEmail(profile: Map<String, Any?>): String? = (profile["email"] as? String)
        ?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && it.length <= 320 && '/' !in it }
}
