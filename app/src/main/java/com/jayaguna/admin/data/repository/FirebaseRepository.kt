package com.jayaguna.admin.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.jayaguna.admin.data.remote.BackendApi
import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.core.security.AccessNotApprovedException
import com.jayaguna.admin.core.device.DeviceIdentity
import com.jayaguna.admin.data.model.*
import com.jayaguna.admin.data.remote.*
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest
import com.jayaguna.admin.data.drive.NativeDriveService
import kotlinx.coroutines.CancellationException

data class Page<T>(val items: List<T>, val cursor: DocumentSnapshot?, val hasMore: Boolean)
data class ProductFilter(val search: String = "", val category: String = "", val status: String = "")

class FirebaseRepository(
    private val context: Context, val auth: FirebaseAuth, private val db: FirebaseFirestore,
    private val api: BackendApi, val device: DeviceIdentity,
    private val drive: NativeDriveService, private val writer: NativeCatalogWriter,
    private val mediaRepository: MediaRepository? = null
) {
    private val pageSize = 25L
    fun requireOnline() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) throw IOException()
    }
    private val requestDevices = mutableMapOf<String, DeviceInfo>()
    suspend fun session(): Account {
        requireOnline()
        val user = auth.currentUser ?: throw BackendException("unauthenticated", "Silakan masuk kembali.")
        val token = user.getIdToken(true).await()
        if (!SessionAccess.isGoogle(token.claims)) throw BackendException("unauthenticated", "Silakan masuk menggunakan akun Google.")
        val email = user.email.orEmpty().lowercase(Locale.ROOT)
        val profile: DocumentSnapshot
        val grant: DocumentSnapshot
        try {
            profile = db.collection("users").document(user.uid).get(Source.SERVER).await()
            grant = db.collection("accessGrants").document(email).get(Source.SERVER).await()
        }
        catch (error: FirebaseFirestoreException) {
            if (error.code in setOf(FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED)) {
                throw BackendException("failed-precondition", "Pemeriksaan izin akun belum tersedia. Hubungi pengelola aplikasi.")
            }
            throw error
        }
        return try { SessionAccess.account(user.uid, email, user.displayName, token.claims, profile.data ?: emptyMap(), grant.data ?: emptyMap()) }
        catch (error: BackendException) {
            if (error.code != "permission-denied") throw error
            val request = db.collection("accessRequests").document(user.uid).get(Source.SERVER).await()
            throw AccessNotApprovedException(AccessIdentity(user.uid, user.displayName ?: email, email,
                if (profile.data?.get("active") == false || grant.data?.get("active") == false) "inactive"
                else profile.data?.get("status") as? String ?: grant.data?.get("status") as? String ?: request.data?.get("status") as? String ?: "unapproved",
                request.data?.get("status") == "pending"))
        }
    }

    /** Only server snapshots trigger revalidation; cached approval can never unlock the app. */
    fun observeAccess(uid: String, onChange: () -> Unit): () -> Unit {
        val email = auth.currentUser?.takeIf { it.uid == uid }?.email?.lowercase(Locale.ROOT).orEmpty()
        val paths = listOf("users/$uid", "accessRequests/$uid") + if (email.isNotBlank()) listOf("accessGrants/$email") else emptyList()
        val registrations = paths.map { path -> db.document(path).addSnapshotListener { doc, error ->
            if (error != null || doc != null && !doc.metadata.isFromCache) onChange()
        } }
        return { registrations.forEach(ListenerRegistration::remove) }
    }

    suspend fun requestAccess(): AccessRequestResult {
        requireOnline()
        val user = auth.currentUser ?: throw BackendException("unauthenticated", "Silakan masuk menggunakan akun Google.")
        val token = user.getIdToken(true).await()
        if (!SessionAccess.isGoogle(token.claims)) throw BackendException("unauthenticated", "Gunakan akun Google yang terverifikasi.")
        val email = user.email.orEmpty().lowercase(Locale.ROOT)
        val name = ((token.claims["name"] as? String)?.takeIf(String::isNotBlank) ?: email).replace(Regex("[\\r\\n]+"), " ").take(160)
        val ref = db.collection("accessRequests").document(user.uid)
        val profileRef = db.collection("users").document(user.uid)
        val grantRef = db.collection("accessGrants").document(email)
        val state = db.runTransaction { tx ->
            val profile = tx.get(profileRef)
            val grant = tx.get(grantRef)
            val existing = tx.get(ref)
            try {
                SessionAccess.account(user.uid, email, user.displayName, token.claims, profile.data ?: emptyMap(), grant.data ?: emptyMap())
                return@runTransaction 2 // Approval may have arrived while the user pressed the button.
            } catch (error: BackendException) { if (error.code != "permission-denied") throw error }
            if (profile.data?.get("active") == false || profile.getString("status") in setOf("rejected", "revoked", "disabled", "inactive")) {
                throw BackendException("permission-denied", "Akses akun telah dinonaktifkan. Hubungi pemilik perusahaan.")
            }
            if (existing.exists()) {
                if (existing.data?.get("status") != "pending") throw BackendException("failed-precondition", "Permohonan sebelumnya telah ditinjau. Hubungi pengelola untuk memeriksa akses.")
                1
            } else {
                val data = mapOf("uid" to user.uid, "name" to name, "email" to email, "status" to "pending", "requestedAt" to FieldValue.serverTimestamp())
                tx.set(ref, data)
                if (!profile.exists()) tx.set(profileRef, data)
                0
            }
        }.await()
        if (state == 2) return AccessRequestResult("Akses akun sudah disetujui. Memeriksa sesi masuk.")
        if (!api.configured) return AccessRequestResult(
            "Permohonan tersimpan. Pengelola dapat menyetujui akun Anda melalui Firebase Console.", state == 1
        )
        val sent = try { api.notifyAccessRequest().optBoolean("emailSent") }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { false }
        return AccessRequestResult(if (sent) "Permohonan tersimpan dan pemberitahuan email telah dikirim ke pengelola."
            else "Permohonan sudah tersimpan dan menunggu persetujuan. Pemberitahuan email belum terkirim; silakan hubungi pengelola.", state == 1)
    }
    suspend fun products(cursor: DocumentSnapshot? = null, filter: ProductFilter = ProductFilter()): Page<Product> {
        var query: Query = db.collection("products").whereEqualTo("archived", false)
        if (filter.search.isNotBlank()) query = query.whereArrayContains("searchTokens", filter.search.trim().lowercase(Locale.ROOT))
        if (filter.category.isNotBlank()) query = query.whereEqualTo("category", filter.category)
        if (filter.status.isNotBlank()) query = query.whereEqualTo("status", filter.status)
        return page(query.orderBy("updatedAt", Query.Direction.DESCENDING), cursor) { it.product() }
    }
    suspend fun partners(cursor: DocumentSnapshot? = null): Page<Partner> = page(db.collection("partners").whereEqualTo("archived", false).orderBy("updatedAt", Query.Direction.DESCENDING), cursor) { it.partner() }
    suspend fun portfolio(category: String, cursor: DocumentSnapshot? = null): Page<PortfolioImage> {
        require(category in setOf("residential", "corporate")) { "Kategori portofolio tidak valid." }
        return page(db.collection("portfolioImages").whereEqualTo("category", category)
            .whereEqualTo("archived", false).orderBy("updatedAt", Query.Direction.DESCENDING), cursor) { it.portfolioImage() }
    }
    suspend fun users(cursor: DocumentSnapshot? = null): Page<AdminUser> {
        var next = cursor
        // Legacy profiles without status remain visible. Skip pages containing only removal records.
        while (true) {
            val raw = page(db.collection("users").orderBy("name"), next) { it.adminUser() }
            val visible = raw.items.filter { it.status != "revoked" }
            if (visible.isNotEmpty() || !raw.hasMore) return raw.copy(items = visible)
            next = raw.cursor
        }
    }
    suspend fun removeAdmin(user: AdminUser, requestId: String) {
        requireOnline()
        NativeAdminWriter(db, auth).remove(user.uid, requestId, requestDevice(requestId))
    }
    suspend fun activity(account: Account, cursor: DocumentSnapshot? = null, filter: ActivityFilter = ActivityFilter()): Page<AuditEntry> {
        var q: Query = db.collection("activityLogs")
        val actor = if (account.role == "OWNER") filter.actorUid else account.uid
        if (actor.isNotBlank()) q = q.whereEqualTo("actorUid", actor)
        if (filter.entityType.isNotBlank()) q = q.whereEqualTo("entityType", filter.entityType)
        if (filter.action.isNotBlank()) q = q.whereEqualTo("action", filter.action)
        fun parseDate(value: String): LocalDate = requireNotNull(runCatching { LocalDate.parse(value) }.getOrNull()) { "Gunakan format tanggal YYYY-MM-DD yang valid." }
        fun day(value: LocalDate): Date = Date.from(value.atStartOfDay(ZoneId.systemDefault()).toInstant())
        val start = filter.startDate.takeIf { it.isNotBlank() }?.let(::parseDate)
        val end = filter.endDate.takeIf { it.isNotBlank() }?.let(::parseDate)
        require(start == null || end == null || !end.isBefore(start)) { "Tanggal akhir tidak boleh sebelum tanggal awal." }
        if (start != null) q = q.whereGreaterThanOrEqualTo("timestamp", day(start))
        if (end != null) q = q.whereLessThan("timestamp", day(end.plusDays(1)))
        return page(q.orderBy("timestamp", Query.Direction.DESCENDING), cursor) { it.audit() }
    }
    private suspend fun <T> page(query: Query, cursor: DocumentSnapshot?, mapper: (DocumentSnapshot) -> T): Page<T> {
        requireOnline()
        val result = (if (cursor == null) query else query.startAfter(cursor)).limit(pageSize).get(Source.SERVER).await()
        return Page(result.documents.map(mapper), result.documents.lastOrNull(), result.size().toLong() == pageSize)
    }
    suspend fun dashboard(account: Account): Dashboard { requireOnline(); return loadFirebaseDashboard(db, account) }
    suspend fun saveProduct(product: Product, requestId: String, progress: (Float) -> Unit) {
        val promoted = mutableMapOf<String, Media>()
        product.images.forEachIndexed { index, media ->
            promoted[media.storagePath] = uploadIfLocal(media, "product", product.id, requestId, index) { value -> progress((index + value) / product.images.size.coerceAtLeast(1)) }
        }
        val prepared = product.copy(images = product.images.map { promoted.getValue(it.storagePath) }, mainImagePath = promoted[product.mainImagePath]?.storagePath ?: "")
        writer.saveProduct(prepared, requestId, requestDevice(requestId))
        cleanRemovedImages("product", product.id, requestId)
    }
    suspend fun savePartner(partner: Partner, requestId: String, progress: (Float) -> Unit) {
        val logo = partner.logo?.let { uploadIfLocal(it, "partner", partner.id, requestId, 0, progress) }
        writer.savePartner(partner.copy(logo = logo), requestId, requestDevice(requestId))
        cleanRemovedImages("partner", partner.id, requestId)
    }
    suspend fun savePortfolio(item: PortfolioImage, requestId: String, progress: (Float) -> Unit) {
        require(!item.archived && item.image != null) { "Pilih gambar portofolio terlebih dahulu." }
        val type = item.entityType
        val image = uploadIfLocal(requireNotNull(item.image), type, item.id, requestId, 0, progress)
        writer.savePortfolio(item.copy(image = image), requestId, requestDevice(requestId))
        cleanRemovedImages(type, item.id, requestId)
        progress(1f)
    }
    suspend fun deletePortfolio(item: PortfolioImage, requestId: String) {
        val type = item.entityType
        writer.archive(type, item.id, item.version, requestId, requestDevice(requestId))
        cleanRemovedImages(type, item.id, requestId)
    }
    // Stable staging references across retries keep the mutation payload identical.
    private val uploads = mutableMapOf<String, Media>()
    private suspend fun uploadIfLocal(media: Media, type: String, id: String, request: String, index: Int, progress: (Float) -> Unit): Media {
        if (!media.storagePath.startsWith("content://")) return media
        val uid = auth.currentUser?.uid ?: throw BackendException("unauthenticated", "Silakan masuk kembali.")
        val key = "$uid:$request:$index:${media.storagePath}"
        uploads[key]?.let { return it }
        requireOnline()
        val uri = media.storagePath.toUri()
        val mime = context.contentResolver.getType(uri) ?: ""
        require(mime in setOf("image/png", "image/jpeg", "image/webp")) { "Gunakan PNG, JPEG, atau WebP." }
        val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 5 * 1024 * 1024) { "Ukuran gambar maksimal 5 MiB." }
                output.write(buffer, 0, count)
            }
            require(output.size() > 0) { "Gambar kosong." }
            output.toByteArray()
        } } ?: throw IOException("Gambar tidak dapat dibaca. Pilih ulang gambar.")
        val sha256 = withContext(Dispatchers.Default) { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) } }
        val uploadRequestId = UUID.nameUUIDFromBytes(key.toByteArray()).toString()
        val existing = writer.findUpload(uploadRequestId)
        val fileId = existing?.driveFileId ?: drive.reserveFileId()
        val slot = writer.reserveMedia(uploadRequestId, requestDevice(request), type, id, mime, bytes.size.toLong(), sha256, "gambar-${index+1}", fileId)
        if (slot.status in setOf("uploaded", "attached")) {
            require(slot.sha256 == sha256) { "Gambar berbeda dari permintaan sebelumnya. Pilih ulang gambar." }
            progress(1f)
            return slot.media.also { uploads[key] = it }
        }
        val uploaded = drive.upload(slot.driveFileId, slot.id, type, id, "gambar-${index+1}", mime, bytes, progress)
        return writer.markUploaded(slot.id, uploaded, sha256).also { uploads[key] = it }
    }
    suspend fun archive(type: String, id: String, version: Long, requestId: String) {
        writer.archive(type, id, version, requestId, requestDevice(requestId))
    }
    private fun requestDevice(requestId: String): DeviceInfo = requestDevices.getOrPut(requestId) { device.info() }
    suspend fun driveStatus() = drive.verifyConnection()
    private suspend fun cleanRemovedImages(type: String, id: String, requestId: String) {
        try {
            writer.detachRemovedMedia(requestId, requestDevice(requestId))
            for (asset in writer.detachedForRequest(type, id, requestId)) {
                val deletion = UUID.nameUUIDFromBytes("$requestId:delete:${asset.id}".toByteArray()).toString()
                val pending = writer.beginMediaDeletion(asset.id, deletion, requestDevice(requestId))
                if (pending.status != "deleted") {
                    drive.trash(pending.id, pending.entityType, pending.sha256)
                    writer.finishMediaDeletion(pending.id, deletion, requestDevice(requestId))
                    mediaRepository?.invalidate("drive/${pending.id}")
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Catalogue commit is confirmed. Retain retryable cleanup metadata. */ }
    }
}
