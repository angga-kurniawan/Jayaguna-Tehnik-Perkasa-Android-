package com.jayaguna.admin.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.data.model.DeviceInfo
import com.jayaguna.admin.data.remote.payload
import kotlinx.coroutines.tasks.await
import java.util.Locale

/** OWNER-only removal, checked again by Firestore Rules in the same atomic audit commit. */
class NativeAdminWriter(private val db: FirebaseFirestore, private val auth: FirebaseAuth) {
    suspend fun remove(uid: String, requestId: String, device: DeviceInfo) {
        require(uid.isNotBlank() && uid.length <= 128 && '/' !in uid) { "UID admin tidak valid." }
        require(requestId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "ID permintaan tidak valid." }
        val user = auth.currentUser ?: throw BackendException("unauthenticated", "Silakan masuk kembali.")
        val claims = user.getIdToken(false).await().claims
        if (!SessionAccess.isGoogle(claims)) throw BackendException("unauthenticated", "Silakan masuk menggunakan akun Google.")
        val ownerUid = user.uid
        val ownerEmail = user.email.orEmpty().lowercase(Locale.ROOT)
        val target = db.collection("users").document(uid)
        val activity = db.collection("activityLogs").document("${ownerUid}_$requestId")
        db.runTransaction { tx ->
            if (auth.currentUser?.uid != ownerUid) throw BackendException("unauthenticated", "Sesi akun telah berubah.")
            val profile = tx.get(db.collection("users").document(ownerUid)).data ?: emptyMap()
            val grant = tx.get(db.collection("accessGrants").document(ownerEmail)).data ?: emptyMap()
            val owner = SessionAccess.account(ownerUid, ownerEmail, user.displayName, claims, profile, grant)
            if (owner.role != "OWNER") throw BackendException("permission-denied", "Hanya OWNER yang dapat menghapus admin.")
            val old = tx.get(target).data ?: throw BackendException("not-found", "Akun admin tidak ditemukan. Perbarui daftar pengguna.")
            val targetGrant = NativeAdminRemovalPolicy.grantEmail(old)?.let {
                tx.get(db.collection("accessGrants").document(it)).data
            } ?: emptyMap()
            NativeAdminRemovalPolicy.requireTarget(owner, uid, old, targetGrant)
            // A lost reply can be retried safely, including after another OWNER removed it.
            if (old["status"] == "revoked" && old["active"] == false) return@runTransaction
            val before = NativeAdminRemovalPolicy.snapshot(uid, old)
            val patch = mapOf(
                "status" to "revoked", "active" to false, "removedBy" to owner.uid,
                "removedAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
                "removalRequestId" to requestId,
            )
            val after = NativeAdminRemovalPolicy.snapshot(uid, old + patch)
            val deviceData = device.payload()
            val log = mapOf(
                "actorUid" to owner.uid, "actorName" to owner.name, "actorEmail" to owner.email, "actorRole" to "OWNER",
                "action" to "removeAdmin", "entityType" to "user", "entityId" to uid,
                "before" to before, "after" to after,
                "changedFields" to mapOf("status" to mapOf("before" to before["status"], "after" to "revoked"),
                    "active" to mapOf("before" to before["active"], "after" to false)),
                "timestamp" to FieldValue.serverTimestamp(), "requestId" to requestId, "device" to deviceData,
            ) + deviceData
            tx.update(target, patch)
            tx.set(activity, log)
        }.await()
    }
}
