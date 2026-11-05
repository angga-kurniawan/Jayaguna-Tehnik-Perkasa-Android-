package com.jayaguna.admin.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.core.validation.Validation
import com.jayaguna.admin.data.model.Account
import com.jayaguna.admin.data.model.DeviceInfo
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Partner
import com.jayaguna.admin.data.model.Product
import com.jayaguna.admin.data.model.PortfolioImage
import com.jayaguna.admin.data.drive.NativeDrivePolicy
import com.jayaguna.admin.data.remote.payload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale

/** A durable reservation is written before Drive receives image bytes. */
data class NativeMediaAsset(
    val id: String,
    val driveFileId: String,
    val entityType: String,
    val entityId: String,
    val folder: String,
    val status: String,
    val expectedMime: String,
    val expectedBytes: Long,
    val originalSha256: String,
    val sha256: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
    val expiresAtMillis: Long,
) {
    val media: Media get() = Media("drive/$id", "", "drive", driveFileId, fileName, mimeType, sizeBytes)
}

data class NativeSaveResult(val id: String, val version: Long)

/**
 * Native catalogue writes are checked again by Firestore Security Rules. No
 * Firebase Admin credential or Google refresh token is used by this class.
 * Activity, public projection and receipt share the entity's atomic commit.
 */
class NativeCatalogWriter(private val db: FirebaseFirestore, private val auth: FirebaseAuth) {
    private data class Identity(val uid: String, val email: String, val displayName: String?, val claims: Map<String, Any?>)
    private val maxBytes = 5L * 1024 * 1024

    private suspend fun identity(): Identity {
        val user = auth.currentUser ?: denied("Silakan masuk kembali.")
        val token = user.getIdToken(false).await()
        if (!SessionAccess.isGoogle(token.claims)) denied("Silakan masuk menggunakan akun Google yang terverifikasi.")
        return Identity(user.uid, user.email.orEmpty().lowercase(Locale.ROOT), user.displayName, token.claims)
    }

    private fun actor(tx: Transaction, identity: Identity): Account {
        val profile = tx.get(db.collection("users").document(identity.uid))
        val data = profile.data ?: emptyMap()
        return try { SessionAccess.account(identity.uid, identity.email, identity.displayName, identity.claims, data) }
        catch (error: BackendException) {
            if (error.code != "permission-denied" || data.containsKey("status") || data["active"] == false) throw error
            val grant = tx.get(db.collection("accessGrants").document(identity.email))
            SessionAccess.account(identity.uid, identity.email, identity.displayName, identity.claims, data, grant.data ?: emptyMap())
        }
    }

    suspend fun findUpload(requestId: String): NativeMediaAsset? {
        requireUuid(requestId)
        val identity = identity()
        val slot = db.collection("driveUploadRequests").document("${identity.uid}_$requestId").get(Source.SERVER).await()
        if (!slot.exists()) return null
        val assetId = slot.getString("assetId") ?: invalid("Sesi unggah tidak valid.")
        return assetForRead("drive/$assetId")
    }

    suspend fun reserveMedia(
        requestId: String,
        device: DeviceInfo,
        entityType: String,
        entityId: String,
        contentType: String,
        bytes: Long,
        sha256: String,
        fileName: String,
        driveFileId: String,
    ): NativeMediaAsset {
        requireUuid(requestId); requireUuid(entityId); requireDriveId(driveFileId)
        require(entityType in NativeDrivePolicy.ENTITY_TYPES && contentType in mimes && bytes in 1..maxBytes && hashPattern.matches(sha256)) { "Metadata unggahan tidak valid." }
        require(fileName.isNotBlank() && fileName.length <= 120) { "Nama gambar tidak valid." }
        requireUuid(device.installationId)
        val identity = identity()
        val fingerprint = NativeWriteCodec.fingerprint(mapOf("operation" to "native-upload-v1", "requestId" to requestId, "device" to device.payload(), "entityType" to entityType, "entityId" to entityId, "contentType" to contentType, "bytes" to bytes, "sha256" to sha256, "fileName" to fileName))
        val slotRef = db.collection("driveUploadRequests").document("${identity.uid}_$requestId")
        val id = UUID.randomUUID().toString()
        val assetRef = db.collection("mediaAssets").document(id)
        val expiry = Timestamp(java.util.Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L))
        return db.runTransaction { tx ->
            val actor = actor(tx, identity)
            val old = tx.get(slotRef)
            if (old.exists()) {
                if (old.getString("hash") != fingerprint) conflict("Permintaan unggah sudah dipakai untuk gambar berbeda.")
                val previousId = old.getString("assetId") ?: invalid("Sesi unggah tidak valid.")
                asset(tx.get(db.collection("mediaAssets").document(previousId)))
            } else {
                val data = mapOf(
                    "id" to id, "driveFileId" to driveFileId, "folder" to folder(entityType), "entityType" to entityType,
                    "entityId" to entityId, "createdBy" to actor.uid, "device" to device.payload(), "requestId" to requestId,
                    "status" to "reserved", "expectedBytes" to bytes, "expectedMime" to contentType,
                    "originalSha256" to sha256, "originalFileName" to fileName, "source" to "android",
                    "createdAt" to FieldValue.serverTimestamp(), "expiresAt" to expiry,
                )
                tx.set(slotRef, mapOf("hash" to fingerprint, "assetId" to id, "createdAt" to FieldValue.serverTimestamp(), "createdBy" to actor.uid, "source" to "android"))
                tx.set(assetRef, data)
                asset(id, data)
            }
        }.await()
    }

    suspend fun markUploaded(assetId: String, media: Media, sha256: String): Media {
        requireUuid(assetId); requireDriveId(media.driveFileId)
        require(media.storagePath == "drive/$assetId" && media.provider == "drive" && media.url.isBlank() && hashPattern.matches(sha256)) { "Metadata gambar Drive tidak valid." }
        val identity = identity()
        val ref = db.collection("mediaAssets").document(assetId)
        return db.runTransaction { tx ->
            val actor = actor(tx, identity)
            val doc = tx.get(ref)
            val old = doc.data ?: invalid("Sesi unggah tidak ditemukan.")
            val existing = asset(doc)
            if (old["createdBy"] != actor.uid) denied("Sesi unggah bukan milik akun ini.")
            if (existing.driveFileId != media.driveFileId || existing.expectedMime != media.mimeType || existing.expectedBytes != media.sizeBytes || existing.originalSha256 != sha256) conflict("Retry harus memakai gambar dan metadata yang sama.")
            if (existing.status in setOf("uploaded", "attached")) {
                if (existing.sha256 != sha256) conflict("Gambar tersimpan berbeda dari unggahan ini.")
                existing.media
            } else {
                if (existing.status != "reserved" || existing.expiresAtMillis <= System.currentTimeMillis()) throw BackendException("upload-session-expired", "Sesi unggah sudah berakhir. Pilih ulang gambar.")
                val auditRef = db.collection("audit_logs").document("upload_$assetId")
                // Rules permit only creation of the matching immutable audit.
                // ADMIN cannot read an audit that does not exist yet.
                val patch = mapOf("status" to "uploaded", "fileName" to media.fileName, "mimeType" to media.mimeType, "sizeBytes" to media.sizeBytes, "sha256" to sha256, "uploadedAt" to FieldValue.serverTimestamp())
                tx.update(ref, patch)
                tx.set(auditRef, mediaAudit(actor, old["device"] as? Map<*, *> ?: emptyMap<Any, Any>(), existing, "UPLOAD_" + mediaActionSuffix(existing.entityType), old["requestId"] as? String ?: ""))
                media.copy(url = "")
            }
        }.await()
    }

    suspend fun assetForRead(path: String): NativeMediaAsset {
        val id = assetId(path)
        identity()
        val doc = db.collection("mediaAssets").document(id).get(Source.SERVER).await()
        return asset(doc)
    }

    suspend fun saveProduct(product: Product, requestId: String, device: DeviceInfo): NativeSaveResult {
        Validation.product(product)
        return save("product", product.id, product.version, product.payload(), requestId, device)
    }

    suspend fun savePartner(partner: Partner, requestId: String, device: DeviceInfo): NativeSaveResult {
        Validation.partner(partner)
        return save("partner", partner.id, partner.version, partner.payload(), requestId, device)
    }

    suspend fun savePortfolio(item: PortfolioImage, requestId: String, device: DeviceInfo): NativeSaveResult {
        require(!item.archived && item.image != null) { "Pilih gambar portofolio terlebih dahulu." }
        return save(item.entityType, item.id, item.version, item.payload(), requestId, device)
    }

    private suspend fun save(type: String, id: String, expectedVersion: Long, payload: Map<String, Any?>, requestId: String, device: DeviceInfo): NativeSaveResult {
        requireUuid(id); requireUuid(requestId); requireUuid(device.installationId)
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE) { "Versi data tidak valid." }
        val identity = identity()
        val operation = when (type) { "product" -> "saveProduct"; "partner" -> "savePartner"; else -> "savePortfolio" }
        val hash = NativeWriteCodec.fingerprint(mapOf("operation" to "native-$operation-v1", "id" to id, "expectedVersion" to expectedVersion, "data" to payload, "requestId" to requestId, "device" to device.payload()))
        val ref = db.collection(collection(type)).document(id)
        val receiptRef = db.collection("operationReceipts").document("${identity.uid}_$requestId")
        val result = db.runTransaction { tx ->
            val actor = actor(tx, identity)
            val previousReceipt = tx.get(receiptRef)
            val oldDoc = tx.get(ref)
            duplicate(previousReceipt, hash)?.let { return@runTransaction it }
            val old = oldDoc.data
            if (((old?.get("version") as? Number)?.toLong() ?: 0L) != expectedVersion || old?.get("archived") == true) aborted()
            if (isPortfolio(type) && (payload["category"] != portfolioCategory(type) || old != null && old["category"] != payload["category"])) invalid("Kategori gambar portofolio tidak dapat dipindahkan. Tambahkan gambar baru pada kategori tujuan.")
            val input = payload.toMutableMap()
            val beforeImages = references(old)
            val newImages = references(input)
            val paths = newImages.map { it["storagePath"] as? String ?: invalid("Referensi gambar tidak valid.") }.filter { it.startsWith("drive/") }.distinct()
            val assets = paths.associateWith { path -> tx.get(db.collection("mediaAssets").document(assetId(path))) }
            val promoted = newImages.map { image ->
                val path = image["storagePath"] as? String ?: invalid("Referensi gambar tidak valid.")
                if (path.startsWith("drive/")) {
                    val value = assets.getValue(path)
                    val imageAsset = asset(value)
                    requireUsableAsset(imageAsset, value.data.orEmpty(), actor, type, id, beforeImages.any { it == imageAsset.media.payload() })
                    imageAsset.media.payload()
                } else {
                    if (isPortfolio(type)) invalid("Gambar portofolio harus disimpan pada Google Drive perusahaan.")
                    beforeImages.find { it["storagePath"] == path } ?: invalid("Pilih gambar melalui pemilih foto aplikasi.")
                }
            }
            if (type == "product") {
                input["images"] = promoted
                input["searchTokens"] = NativeWriteCodec.searchTokens(input["name"] as String, input["brand"] as String)
            } else if (type == "partner") input["logo"] = promoted.singleOrNull() ?: invalid("Logo mitra wajib diisi.")
            else input["image"] = promoted.singleOrNull() ?: invalid("Gambar portofolio wajib diisi.")
            val next = input + mapOf("id" to id, "archived" to false, "version" to expectedVersion + 1, "createdAt" to (old?.get("createdAt") ?: FieldValue.serverTimestamp()), "updatedAt" to FieldValue.serverTimestamp(), "createdBy" to (old?.get("createdBy") ?: actor.uid), "updatedBy" to actor.uid, "lastRequestId" to requestId)
            val action = if (old == null) "add" else if (!isPortfolio(type) && old[if (type == "product") "status" else "active"] != next[if (type == "product") "status" else "active"]) "status" else "edit"
            val result = NativeSaveResult(id, expectedVersion + 1)
            // References are committed with the catalogue first. Per-image
            // attachment then uses bounded transactions below, so an eight-
            // image save stays within Firestore's rules evaluation limits.
            tx.set(ref, next)
            writeProjection(tx, type, id, next)
            tx.set(db.collection("activityLogs").document("${actor.uid}_$requestId"), activity(actor, device, type, id, action, requestId, old, next))
            tx.set(receiptRef, receipt(hash, result, type, operation))
            result
        }.await()
        // A confirmed catalogue commit must not be presented as failed merely
        // because orphan cleanup loses connectivity. Repeating the receipt may
        // resume it; images remain safely present until a later cleanup.
        try { attachUploadedMedia(requestId, device) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* The committed entity remains the authority for references. */ }
        try { detachRemovedMedia(requestId, device) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Retain old media; never remove a live reference. */ }
        return result
    }

    /** Resume pending registry attachment only after the catalogue commit. */
    suspend fun attachUploadedMedia(requestId: String, device: DeviceInfo): List<NativeMediaAsset> {
        requireUuid(requestId); requireUuid(device.installationId)
        val identity = identity()
        val history = db.collection("activityLogs").document("${identity.uid}_$requestId").get(Source.SERVER).await()
        if (!history.exists() || history.getString("actorUid") != identity.uid) return emptyList()
        val images = references(history.get("after") as? Map<*, *>).filter { (it["storagePath"] as? String)?.startsWith("drive/") == true }
        val attached = mutableListOf<NativeMediaAsset>()
        for (image in images) {
            val mediaId = assetId(image["storagePath"] as String)
            val result = db.runTransaction { tx ->
                val actor = actor(tx, identity)
                val log = tx.get(history.reference)
                val receipt = tx.get(db.collection("operationReceipts").document("${actor.uid}_$requestId"))
                val type = log.getString("entityType") ?: invalid("Riwayat perubahan tidak valid.")
                val entityId = log.getString("entityId") ?: invalid("Riwayat perubahan tidak valid.")
                val entity = tx.get(db.collection(collection(type)).document(entityId))
                val doc = tx.get(db.collection("mediaAssets").document(mediaId))
                if (!receipt.exists() || log.getString("actorUid") != actor.uid || log.getString("requestId") != requestId || receipt.getString("entityType") != type || receipt.getString("entityId") != entityId) denied("Riwayat penyimpanan gambar tidak cocok.")
                if (!doc.exists() || !entity.exists()) return@runTransaction null
                val value = asset(doc)
                if (value.entityType != type || value.entityId != entityId) denied("Gambar tidak cocok dengan produk atau mitra ini.")
                val canonical = value.media.payload()
                if (references(log.get("after") as? Map<*, *>).none { it == canonical } || references(entity.data).none { it == canonical }) return@runTransaction null
                if (value.status == "attached") return@runTransaction value
                if (value.status != "uploaded" || doc.getString("createdBy") != actor.uid) return@runTransaction null
                tx.update(doc.reference, mapOf("status" to "attached", "attachedAt" to FieldValue.serverTimestamp(), "lastRequestId" to requestId))
                value.copy(status = "attached")
            }.await()
            if (result != null) attached += result
        }
        return attached
    }

    suspend fun archive(entityType: String, id: String, expectedVersion: Long, requestId: String, device: DeviceInfo): NativeSaveResult {
        require(entityType in NativeDrivePolicy.ENTITY_TYPES); requireUuid(id); requireUuid(requestId); requireUuid(device.installationId)
        require(expectedVersion > 0 && expectedVersion < Long.MAX_VALUE)
        val identity = identity()
        val operation = when (entityType) { "product" -> "archiveProduct"; "partner" -> "archivePartner"; else -> "archivePortfolio" }
        val hash = NativeWriteCodec.fingerprint(mapOf("operation" to "native-$operation-v1", "id" to id, "expectedVersion" to expectedVersion, "requestId" to requestId, "device" to device.payload()))
        val ref = db.collection(collection(entityType)).document(id)
        val receiptRef = db.collection("operationReceipts").document("${identity.uid}_$requestId")
        val result = db.runTransaction { tx ->
            val actor = actor(tx, identity)
            val receiptDoc = tx.get(receiptRef)
            val doc = tx.get(ref)
            duplicate(receiptDoc, hash)?.let { return@runTransaction it }
            val old = doc.data ?: invalid("Data tidak ditemukan.")
            if ((old["version"] as? Number)?.toLong() != expectedVersion || old["archived"] == true) aborted()
            if (isPortfolio(entityType) && old["category"] != portfolioCategory(entityType)) invalid("Kategori portofolio tidak cocok dengan folder gambar.")
            val result = NativeSaveResult(id, expectedVersion + 1)
            val next = old + mapOf("archived" to true, "version" to result.version, "updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to actor.uid, "lastRequestId" to requestId) + if (isPortfolio(entityType)) mapOf("image" to null) else emptyMap()
            tx.set(ref, next)
            tx.delete(db.collection(publicCollection(entityType)).document(id))
            tx.set(db.collection("activityLogs").document("${actor.uid}_$requestId"), activity(actor, device, entityType, id, "archive", requestId, old, next))
            tx.set(receiptRef, receipt(hash, result, entityType, operation))
            result
        }.await()
        if (isPortfolio(entityType)) {
            try { detachRemovedMedia(requestId, device) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Keep the removed image for retryable cleanup after the confirmed archive. */ }
        }
        return result
    }

    /** Small, resumable cleanup transactions stay below the rules' call budget. */
    suspend fun detachRemovedMedia(requestId: String, device: DeviceInfo): List<NativeMediaAsset> {
        requireUuid(requestId)
        val identity = identity()
        val history = db.collection("activityLogs").document("${identity.uid}_$requestId").get(Source.SERVER).await()
        if (!history.exists() || history.getString("actorUid") != identity.uid) return emptyList()
        val previous = references(history.get("before") as? Map<*, *>)
        val next = references(history.get("after") as? Map<*, *>)
        val removed = previous.filter { image -> (image["storagePath"] as? String)?.startsWith("drive/") == true && next.none { it["storagePath"] == image["storagePath"] } }
        val replacement = next.any { image -> previous.none { it["storagePath"] == image["storagePath"] } }
        val result = mutableListOf<NativeMediaAsset>()
        for (image in removed) {
            val assetId = assetId(image["storagePath"] as String)
            val detached = db.runTransaction { tx ->
                val actor = actor(tx, identity)
                val log = tx.get(history.reference)
                val receipt = tx.get(db.collection("operationReceipts").document("${actor.uid}_$requestId"))
                val type = log.getString("entityType") ?: invalid("Riwayat perubahan tidak valid.")
                val id = log.getString("entityId") ?: invalid("Riwayat perubahan tidak valid.")
                val current = tx.get(db.collection(collection(type)).document(id))
                val doc = tx.get(db.collection("mediaAssets").document(assetId))
                val auditRef = db.collection("audit_logs").document("${actor.uid}_${requestId}_remove_$assetId")
                if (!receipt.exists() || log.getString("actorUid") != actor.uid || log.getString("requestId") != requestId) denied("Riwayat perubahan tidak cocok.")
                if (references(current.data).any { it["storagePath"] == "drive/$assetId" }) return@runTransaction null
                if (!doc.exists()) return@runTransaction null
                val value = asset(doc)
                if (value.entityType != type || value.entityId != id) denied("Gambar tidak cocok dengan data ini.")
                if (value.status == "deleted" || value.status == "deleting") return@runTransaction null
                if (value.status == "detached") return@runTransaction value
                if (value.status !in setOf("uploaded", "attached")) return@runTransaction null
                val action = (if (replacement) "REPLACE_" else "REMOVE_") + mediaActionSuffix(type)
                tx.update(doc.reference, mapOf("status" to "detached", "detachedAt" to FieldValue.serverTimestamp(), "deleteAfter" to Timestamp(java.util.Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L)), "lastRequestId" to requestId))
                tx.set(auditRef, mediaAudit(actor, log.get("device") as? Map<*, *> ?: device.payload(), value, action, requestId))
                value.copy(status = "detached")
            }.await()
            if (detached != null) result += detached
        }
        return result
    }

    /** No query/index is required: only assets removed by this immutable log. */
    suspend fun detachedForRequest(entityType: String, entityId: String, requestId: String): List<NativeMediaAsset> {
        require(entityType in NativeDrivePolicy.ENTITY_TYPES); requireUuid(entityId); requireUuid(requestId)
        val identity = identity()
        val log = db.collection("activityLogs").document("${identity.uid}_$requestId").get(Source.SERVER).await()
        if (!log.exists() || log.getString("actorUid") != identity.uid || log.getString("entityType") != entityType || log.getString("entityId") != entityId) return emptyList()
        val before = references(log.get("before") as? Map<*, *>)
        val after = references(log.get("after") as? Map<*, *>)
        val removed = before.mapNotNull { it["storagePath"] as? String }.filter { it.startsWith("drive/") && after.none { image -> image["storagePath"] == it } }.distinct()
        val result = mutableListOf<NativeMediaAsset>()
        for (path in removed) {
            val doc = db.collection("mediaAssets").document(assetId(path)).get(Source.SERVER).await()
            if (doc.exists() && doc.getString("lastRequestId") == requestId && doc.getString("status") in setOf("detached", "deleting")) result += asset(doc)
        }
        return result
    }

    /** Persist deleting before calling Drive; a lost Drive reply can be retried. */
    suspend fun beginMediaDeletion(assetId: String, requestId: String, device: DeviceInfo): NativeMediaAsset {
        requireUuid(assetId); requireUuid(requestId); requireUuid(device.installationId)
        val identity = identity()
        return db.runTransaction { tx ->
            val actor = actor(tx, identity)
            val doc = tx.get(db.collection("mediaAssets").document(assetId))
            val value = asset(doc)
            val raw = doc.data.orEmpty()
            val entity = tx.get(db.collection(collection(value.entityType)).document(value.entityId))
            if (raw["createdBy"] != actor.uid && actor.role != "OWNER") denied("Hanya pembuat unggahan atau OWNER dapat menghapus gambar ini.")
            if (references(entity.data).any { it["storagePath"] == "drive/$assetId" }) invalid("Gambar masih dipakai. Simpan penggantian atau penghapusan referensi terlebih dahulu.")
            if (value.status == "deleted") return@runTransaction value
            if (value.status == "deleting") {
                if (raw["deleteActorUid"] != actor.uid || raw["deleteRequestId"] != requestId || raw["deleteDevice"] != device.payload()) conflict("Pembersihan gambar sedang dilakukan dengan permintaan lain.")
                return@runTransaction value
            }
            if (value.status !in setOf("uploaded", "detached") || !hashPattern.matches(value.sha256)) invalid("Gambar belum siap dibersihkan.")
            tx.update(doc.reference, mapOf("status" to "deleting", "deleteRequestId" to requestId, "deleteDevice" to device.payload(), "deleteActorUid" to actor.uid, "deleteStartedAt" to FieldValue.serverTimestamp()))
            value.copy(status = "deleting")
        }.await()
    }

    /** Call only after the company backend confirms trash for this registered asset. */
    suspend fun finishMediaDeletion(assetId: String, requestId: String, device: DeviceInfo) {
        requireUuid(assetId); requireUuid(requestId); requireUuid(device.installationId)
        val identity = identity()
        db.runTransaction { tx ->
            val actor = actor(tx, identity)
            val doc = tx.get(db.collection("mediaAssets").document(assetId))
            val value = asset(doc)
            val raw = doc.data.orEmpty()
            val entity = tx.get(db.collection(collection(value.entityType)).document(value.entityId))
            val auditRef = db.collection("audit_logs").document("delete_$assetId")
            if (value.status == "deleted") return@runTransaction
            if (value.status != "deleting" || raw["deleteActorUid"] != actor.uid || raw["deleteRequestId"] != requestId || raw["deleteDevice"] != device.payload()) denied("Permintaan penghapusan tidak cocok dengan akun ini.")
            if (raw["createdBy"] != actor.uid && actor.role != "OWNER") denied("Akun tidak memiliki izin menghapus gambar ini.")
            if (references(entity.data).any { it["storagePath"] == "drive/$assetId" }) invalid("Gambar kembali digunakan. Metadata penghapusan tidak diteruskan.")
            tx.update(doc.reference, mapOf("status" to "deleted", "deletedAt" to FieldValue.serverTimestamp()))
            tx.set(auditRef, mediaAudit(actor, device.payload(), value, "DELETE_" + mediaActionSuffix(value.entityType), requestId))
        }.await()
    }

    private fun requireUsableAsset(asset: NativeMediaAsset, raw: Map<String, Any>, actor: Account, type: String, id: String, alreadyReferenced: Boolean) {
        if (asset.entityType != type || asset.entityId != id || asset.status !in setOf("uploaded", "attached") || asset.status == "uploaded" && raw["createdBy"] != actor.uid && !alreadyReferenced) invalid("Gambar belum selesai diunggah atau tidak cocok dengan produk/mitra ini.")
    }

    private fun writeProjection(tx: Transaction, type: String, id: String, value: Map<String, Any?>) {
        val target = db.collection(publicCollection(type)).document(id)
        val projection = NativeWriteCodec.publicProjection(type, id, value)
        if (projection == null) tx.delete(target) else tx.set(target, projection)
    }

    private fun activity(actor: Account, device: DeviceInfo, type: String, id: String, action: String, requestId: String, before: Map<String, Any>?, after: Map<String, Any?>): Map<String, Any?> {
        val previous = if (before == null) emptyMap() else NativeWriteCodec.snapshot(type, before)
        val next = NativeWriteCodec.snapshot(type, after)
        val changed = (previous.keys + next.keys).filter { NativeWriteCodec.canonical(previous[it]) != NativeWriteCodec.canonical(next[it]) }.associateWith { mapOf("before" to previous[it], "after" to next[it]) }
        return mapOf("actorUid" to actor.uid, "actorName" to actor.name, "actorEmail" to actor.email, "actorRole" to actor.role, "action" to action, "entityType" to type, "entityId" to id, "before" to previous, "after" to next, "changedFields" to changed, "timestamp" to FieldValue.serverTimestamp(), "requestId" to requestId, "device" to device.payload()) + device.payload()
    }

    private fun receipt(hash: String, result: NativeSaveResult, type: String, operation: String): Map<String, Any?> = mapOf("hash" to hash, "result" to mapOf("id" to result.id, "version" to result.version), "entityType" to type, "entityId" to result.id, "operation" to operation, "createdAt" to FieldValue.serverTimestamp())

    private fun duplicate(doc: DocumentSnapshot, hash: String): NativeSaveResult? {
        if (!doc.exists()) return null
        if (doc.getString("hash") != hash) conflict("Permintaan sudah digunakan untuk data berbeda.")
        val result = doc.get("result") as? Map<*, *> ?: invalid("Hasil penyimpanan tidak valid.")
        return NativeSaveResult(result["id"] as? String ?: invalid("Hasil penyimpanan tidak valid."), (result["version"] as? Number)?.toLong() ?: invalid("Hasil penyimpanan tidak valid."))
    }

    private fun mediaAudit(actor: Account, device: Map<*, *>, value: NativeMediaAsset, action: String, requestId: String): Map<String, Any?> = mapOf("userId" to actor.uid, "userEmail" to actor.email, "actorName" to actor.name, "actorRole" to actor.role, "action" to action, "targetId" to value.entityId, "entityType" to value.entityType, "driveFileId" to value.driveFileId, "mediaId" to value.id, "deviceId" to device["installationId"], "device" to device, "status" to "SUCCESS", "timestamp" to FieldValue.serverTimestamp(), "requestId" to requestId)

    private fun asset(doc: DocumentSnapshot): NativeMediaAsset = asset(doc.id, doc.data ?: invalid("Gambar atau sesi unggah tidak ditemukan."))
    private fun asset(id: String, raw: Map<String, Any?>): NativeMediaAsset {
        val type = raw["entityType"] as? String ?: invalid("Jenis media tidak valid.")
        val file = raw["driveFileId"] as? String ?: invalid("ID berkas gambar tidak tersedia.")
        val entityId = raw["entityId"] as? String ?: invalid("Data gambar tidak valid.")
        requireUuid(id); requireDriveId(file); requireUuid(entityId)
        if (type !in NativeDrivePolicy.ENTITY_TYPES || raw["folder"] != folder(type)) invalid("Folder gambar tidak cocok dengan perusahaan.")
        return NativeMediaAsset(id, file, type, entityId, folder(type), raw["status"] as? String ?: "", raw["expectedMime"] as? String ?: "", (raw["expectedBytes"] as? Number)?.toLong() ?: 0, raw["originalSha256"] as? String ?: "", raw["sha256"] as? String ?: "", raw["mimeType"] as? String ?: "", raw["fileName"] as? String ?: "", (raw["sizeBytes"] as? Number)?.toLong() ?: 0, (raw["expiresAt"] as? Timestamp)?.toDate()?.time ?: 0)
    }

    private fun folder(type: String) = NativeDrivePolicy.folder(type)
    private fun isPortfolio(type: String) = type in setOf("portfolioResidential", "portfolioCorporate")
    private fun portfolioCategory(type: String) = when (type) { "portfolioResidential" -> "residential"; "portfolioCorporate" -> "corporate"; else -> invalid("Kategori portofolio tidak valid.") }
    private fun collection(type: String) = when (type) { "product" -> "products"; "partner" -> "partners"; "portfolioResidential", "portfolioCorporate" -> "portfolioImages"; else -> invalid("Jenis data tidak valid.") }
    private fun publicCollection(type: String) = when (type) { "product" -> "publicProducts"; "partner" -> "publicPartners"; "portfolioResidential", "portfolioCorporate" -> "publicPortfolioImages"; else -> invalid("Jenis data tidak valid.") }
    private fun mediaActionSuffix(type: String) = when (type) { "product" -> "PRODUCT_IMAGE"; "partner" -> "PARTNER_LOGO"; "portfolioResidential", "portfolioCorporate" -> "PORTFOLIO_IMAGE"; else -> invalid("Jenis media tidak valid.") }
    private fun references(data: Map<*, *>?): List<Map<*, *>> = (data?.get("images") as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: ((data?.get("logo") ?: data?.get("image")) as? Map<*, *>)?.let { listOf(it) } ?: emptyList()
    private fun assetId(path: String): String { if (!path.startsWith("drive/")) invalid("Referensi gambar tidak valid."); return path.removePrefix("drive/").also(::requireUuid) }
    private fun requireUuid(value: String) { require(uuidPattern.matches(value)) { "ID data tidak valid." } }
    private fun requireDriveId(value: String) { require(drivePattern.matches(value)) { "ID Google Drive tidak valid." } }
    private fun invalid(message: String): Nothing = throw BackendException("failed-precondition", message)
    private fun denied(message: String): Nothing = throw BackendException("permission-denied", message)
    private fun conflict(message: String): Nothing = throw BackendException("already-exists", message)
    private fun aborted(): Nothing = throw BackendException("aborted", "Data telah berubah atau diarsipkan. Muat ulang sebelum menyimpan.")

    private companion object {
        val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val drivePattern = Regex("[A-Za-z0-9_-]{10,200}")
        val hashPattern = Regex("[0-9a-f]{64}")
        val mimes = setOf("image/png", "image/jpeg", "image/webp")
    }
}

/** Pure helpers also define the exact public/audit data boundary. */
internal object NativeWriteCodec {
    private val productFields = listOf("name", "brand", "model", "category", "capacityPk", "condition", "price", "description", "specifications", "images", "mainImagePath", "status")
    private val partnerFields = listOf("name", "logo", "altText", "website", "displayOrder", "active")
    private val portfolioFields = listOf("category", "image")
    fun snapshot(type: String, value: Map<String, Any?>): Map<String, Any?> = (listOf("id") + fields(type) + listOf("archived", "version")).associateWith { value[it] }
    fun publicProjection(type: String, id: String, value: Map<String, Any?>): Map<String, Any?>? {
        val visible = when (type) {
            "product" -> value["status"] == "active"
            "partner" -> value["active"] == true
            "portfolioResidential", "portfolioCorporate" -> value["image"] is Map<*, *>
            else -> throw IllegalArgumentException("Jenis data tidak valid.")
        }
        if (value["archived"] == true || !visible) return null
        val media = when (type) {
            "product" -> (value["images"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("storagePath") } ?: emptyList()
            "partner" -> listOfNotNull((value["logo"] as? Map<*, *>)?.get("storagePath"))
            else -> listOfNotNull((value["image"] as? Map<*, *>)?.get("storagePath"))
        }
        return fields(type).associateWith { value[it] } + mapOf("id" to id, "archived" to false, "createdAt" to value["createdAt"], "updatedAt" to value["updatedAt"], "mediaPaths" to media)
    }
    fun searchTokens(name: String, brand: String): List<String> = listOf(name, brand).flatMap { value -> val lower = value.trim().lowercase(java.util.Locale.ROOT); listOf(lower) + lower.split(Regex("""\s+""")) }.flatMap { value -> (1..minOf(value.length, 50)).map { value.take(it) } }.distinct().take(1000)
    fun fingerprint(value: Any?): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + canonical(it.value) }
        is List<*> -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> throw IllegalArgumentException("Nilai fingerprint tidak didukung.")
    }
    private fun fields(type: String) = when (type) {
        "product" -> productFields
        "partner" -> partnerFields
        "portfolioResidential", "portfolioCorporate" -> portfolioFields
        else -> throw IllegalArgumentException("Jenis data tidak valid.")
    }
    private fun quote(value: String) = buildString {
        append('"')
        value.forEach { char -> when (char) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000C' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
        } }
        append('"')
    }
}
