package com.jayaguna.admin.data.drive

import android.graphics.BitmapFactory
import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.repository.NativeCatalogWriter
import com.jayaguna.admin.data.repository.NativeMediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.UUID

data class DriveConnection(val connected: Boolean = true, val accountEmail: String)
data class DriveFileMetadata(
    val id: String, val name: String, val mimeType: String, val sizeBytes: Long,
    val parents: List<String>, val ownerEmails: List<String>, val sha256: String,
    val mediaId: String, val entityId: String, val entityType: String,
    val trashed: Boolean, val canAddChildren: Boolean, val publicAccess: Boolean,
    val md5Checksum: String = "", val canTrash: Boolean = false,
    val application: String = "", val hasAppProperties: Boolean = false,
)

/** Drive authorization belongs to the current Google login; no public backend is required. */
class NativeDriveService(private val writer: NativeCatalogWriter, private val authorization: DriveAuthorizationManager) {
    private val http = NativeDriveTransport(authorization::accessToken, authorization::invalidateToken, authorization::currentSession)

    suspend fun verifyConnection(): DriveConnection = http.inSession {
        val email = http.request("/drive/v3/about?fields=user(emailAddress)").json()
            .optJSONObject("user")?.optString("emailAddress").orEmpty().lowercase(java.util.Locale.ROOT)
        if (email.isBlank() || email != authorization.currentEmail().lowercase(java.util.Locale.ROOT)) {
            throw BackendException("drive-authorization-required", "Akun Google Drive berbeda dari akun yang dipakai masuk. Izinkan Drive menggunakan akun yang sama.")
        }
        for (type in NativeDrivePolicy.ENTITY_TYPES) verifyFolder(NativeDrivePolicy.folder(type))
        DriveConnection(accountEmail = email)
    }

    suspend fun reserveFileId(): String = http.inSession {
        val ids = http.request("/drive/v3/files/generateIds?count=1&space=drive&type=files").json().optJSONArray("ids")
        NativeDrivePolicy.fileId(ids?.optString(0).orEmpty())
    }

    /** A Firestore registry UUID is required; callers cannot pass arbitrary Drive IDs. */
    suspend fun metadata(assetId: String): DriveFileMetadata = http.inSession {
        val asset = asset(assetId)
        if (asset.status !in setOf("reserved", "uploaded", "attached", "detached", "deleting")) unavailable()
        privateMetadata(asset.driveFileId).also { NativeDrivePolicy.verifyAssetFile(asset, it) }
    }

    suspend fun upload(fileId: String, mediaId: String, entityType: String, entityId: String, fileName: String,
                       mimeType: String, bytes: ByteArray, progress: (Float) -> Unit = {}): Media = http.inSession {
        val asset = asset(mediaId)
        withContext(Dispatchers.Default) {
            NativeDrivePolicy.verifyUpload(asset, fileId, entityType, entityId, mimeType, bytes)
            validateImage(bytes, mimeType)
        }
        val name = NativeDrivePolicy.storedFileName(fileName, mimeType)
        verifyFolder(asset.folder)
        val boundary = "jayaguna_" + UUID.randomUUID().toString().replace("-", "")
        val properties = JSONObject().put("application", "jayaguna-admin").put("assetId", mediaId)
            .put("entityId", entityId).put("entityType", entityType).put("sha256", asset.originalSha256)
        val metadata = JSONObject().put("id", fileId).put("name", name).put("mimeType", mimeType)
            .put("parents", JSONArray().put(asset.folder)).put("appProperties", properties)
        val body = withContext(Dispatchers.Default) {
            ByteArrayOutputStream(bytes.size + 2048).use { output ->
                output.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: $mimeType\r\n\r\n".toByteArray())
                output.write(bytes)
                output.write("\r\n--$boundary--\r\n".toByteArray())
                output.toByteArray()
            }
        }
        val response = http.request("/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=id", "POST", body,
            "multipart/related; boundary=$boundary", allowConflict = true, progress = progress)
        if (response.status != 409 && response.json().optString("id") != fileId) invalid()
        // A timeout can mean the first upload succeeded. Reuse the reserved ID,
        // then verify the existing object and bytes before committing Firestore.
        val file = privateMetadata(fileId)
        NativeDrivePolicy.verifyAssetFile(asset, file)
        val confirmedBytes = read(fileId)
        withContext(Dispatchers.Default) {
            if (file.md5Checksum != NativeDrivePolicy.md5(bytes)) invalid()
            NativeDrivePolicy.verifyAssetBytes(asset, confirmedBytes)
        }
        progress(1f)
        Media("drive/$mediaId", "", "drive", fileId, file.name, mimeType, bytes.size.toLong())
    }

    suspend fun download(assetId: String, entityType: String, expectedSha256: String? = null): ByteArray = http.inSession {
        val asset = asset(assetId)
        NativeDrivePolicy.verifyRead(asset, entityType, expectedSha256)
        val file = privateMetadata(asset.driveFileId)
        NativeDrivePolicy.verifyAssetFile(asset, file)
        val bytes = read(asset.driveFileId)
        withContext(Dispatchers.Default) {
            NativeDrivePolicy.verifyAssetBytes(asset, bytes)
            if (file.md5Checksum.isNotBlank() && file.md5Checksum != NativeDrivePolicy.md5(bytes)) invalid()
        }
        bytes
    }

    suspend fun trash(assetId: String, entityType: String, expectedSha256: String? = null): Unit = http.inSession {
        val asset = asset(assetId)
        NativeDrivePolicy.verifyDeletion(asset, entityType, expectedSha256)
        suspend fun deletionMetadata(): DriveFileMetadata = try { privateMetadata(asset.driveFileId) }
        catch (error: BackendException) {
            if (error.code != "not-found") throw error
            // Drive deliberately uses 404 for lost access too. Keep the registry
            // retryable until a matching, readable trashed object is confirmed.
            throw BackendException("failed-precondition", "Penghapusan belum dapat diverifikasi. Periksa akses Drive lalu coba pembersihan kembali.")
        }
        val file = deletionMetadata()
        NativeDrivePolicy.verifyAssetFile(asset, file, allowTrashed = true)
        if (file.trashed) return@inSession
        if (!file.canTrash) throw BackendException("permission-denied", "Akun Google ini tidak dapat memindahkan gambar ke sampah. Gunakan akun pemilik berkas untuk pembersihan.")
        val bytes = read(asset.driveFileId)
        withContext(Dispatchers.Default) { NativeDrivePolicy.verifyAssetBytes(asset, bytes) }
        http.request("/drive/v3/files/${asset.driveFileId}?supportsAllDrives=true&fields=id,trashed", "PATCH",
            JSONObject().put("trashed", true).toString().toByteArray())
        val confirmed = deletionMetadata()
        NativeDrivePolicy.verifyAssetFile(asset, confirmed, allowTrashed = true)
        if (!confirmed.trashed) throw BackendException("failed-precondition", "Google Drive belum mengonfirmasi gambar berada di sampah. Coba pembersihan kembali.")
    }

    private suspend fun asset(id: String): NativeMediaAsset {
        NativeDrivePolicy.assetId(id)
        return writer.assetForRead("drive/$id")
    }
    private suspend fun read(id: String): ByteArray = http.request("/drive/v3/files/${NativeDrivePolicy.fileId(id)}?alt=media&supportsAllDrives=true", image = true).bytes
    private suspend fun verifyFolder(id: String) = NativeDrivePolicy.verifyFolder(privateMetadata(id), id)

    /** Permissions embedded by files.get can be incomplete, so inspect every page. */
    private suspend fun privateMetadata(id: String): DriveFileMetadata {
        NativeDrivePolicy.fileId(id)
        val fields = "id,name,mimeType,size,trashed,parents,md5Checksum,appProperties,owners(emailAddress),capabilities(canAddChildren,canTrash)"
        val json = http.request("/drive/v3/files/$id?supportsAllDrives=true&fields=${encode(fields)}").json()
        if (json.optString("id") != id || !json.has("trashed")) invalid()
        var pageToken = ""
        var publicAccess = false
        var complete = false
        for (page in 0 until 20) {
            val suffix = if (pageToken.isBlank()) "" else "&pageToken=${encode(pageToken)}"
            val permissions = http.request("/drive/v3/files/$id/permissions?supportsAllDrives=true&pageSize=100&fields=${encode("permissions(type),nextPageToken")}$suffix").json()
            val values = permissions.optJSONArray("permissions") ?: invalid()
            for (index in 0 until values.length()) {
                val type = values.optJSONObject(index)?.optString("type").orEmpty()
                if (type !in setOf("user", "group", "domain", "anyone")) invalid()
                if (type in setOf("domain", "anyone")) publicAccess = true
            }
            pageToken = permissions.optString("nextPageToken")
            if (pageToken.isBlank()) { complete = true; break }
        }
        if (!complete) throw BackendException("failed-precondition", "Daftar izin berkas Drive melebihi batas pemeriksaan.")
        val properties = json.optJSONObject("appProperties")
        val parents = json.optJSONArray("parents")
        val owners = json.optJSONArray("owners")
        return DriveFileMetadata(id, json.optString("name"), json.optString("mimeType"), json.optString("size").toLongOrNull() ?: 0,
            (0 until (parents?.length() ?: 0)).map { parents!!.getString(it) },
            (0 until (owners?.length() ?: 0)).map { owners!!.getJSONObject(it).optString("emailAddress").lowercase(java.util.Locale.ROOT) },
            properties?.optString("sha256").orEmpty(), properties?.optString("assetId").orEmpty(), properties?.optString("entityId").orEmpty(), properties?.optString("entityType").orEmpty(),
            json.optBoolean("trashed"), json.optJSONObject("capabilities")?.optBoolean("canAddChildren") == true, publicAccess,
            json.optString("md5Checksum"), json.optJSONObject("capabilities")?.optBoolean("canTrash") == true,
            properties?.optString("application").orEmpty(), properties != null && properties.length() > 0)
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun invalid(): Nothing = throw BackendException("failed-precondition", "Berkas Drive tidak cocok dengan registrasi gambar perusahaan.")
    private fun unavailable(): Nothing = throw BackendException("not-found", "Gambar tidak tersedia.")
    private fun validateImage(bytes: ByteArray, mimeType: String) {
        require(bytes.isNotEmpty() && bytes.size <= NativeDrivePolicy.MAX_IMAGE_BYTES && mimeType in NativeDrivePolicy.IMAGE_TYPES && NativeDrivePolicy.imageHeader(bytes, mimeType)) { "Gunakan PNG, JPEG, atau WebP maksimal 5 MiB." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000L) { "Gambar rusak atau dimensinya melebihi 40 megapiksel." }
    }
}
