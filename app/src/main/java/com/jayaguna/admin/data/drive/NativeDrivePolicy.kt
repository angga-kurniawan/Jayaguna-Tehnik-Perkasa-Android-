package com.jayaguna.admin.data.drive

import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.data.repository.NativeMediaAsset
import java.security.MessageDigest

/** Fixed company folders and validation for registered, private Drive images. */
object NativeDrivePolicy {
    const val OWNER_EMAIL = "mtahakurniawan@gmail.com"
    const val PRODUCTS_FOLDER = "1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd"
    const val PARTNERS_FOLDER = "1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6"
    const val PORTFOLIO_RESIDENTIAL_FOLDER = "1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx"
    const val PORTFOLIO_CORPORATE_FOLDER = "10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK"
    val ENTITY_TYPES: Set<String> = setOf("product", "partner", "portfolioResidential", "portfolioCorporate")
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    val IMAGE_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/webp")
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun assetId(value: String): String {
        if (!value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            fail("invalid-argument", "ID gambar tidak valid.")
        }
        return value
    }

    fun fileId(value: String): String {
        if (!value.matches(Regex("[A-Za-z0-9_-]{10,200}"))) fail("invalid-argument", "ID berkas Google Drive tidak valid.")
        return value
    }

    fun folder(entityType: String): String = when (entityType) {
        "product" -> PRODUCTS_FOLDER
        "partner" -> PARTNERS_FOLDER
        "portfolioResidential" -> PORTFOLIO_RESIDENTIAL_FOLDER
        "portfolioCorporate" -> PORTFOLIO_CORPORATE_FOLDER
        else -> fail("invalid-argument", "Jenis gambar tidak valid.")
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    internal fun storedFileName(name: String, mime: String): String {
        require(name.isNotBlank() && name.length <= 120 && name.none { it == '\r' || it == '\n' }) { "Nama gambar tidak valid." }
        val extension = when (mime) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"
            else -> fail("invalid-argument", "Jenis gambar tidak valid.")
        }
        val dot = name.lastIndexOf('.')
        val stem = (if (dot < 0) name else name.substring(0, dot))
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(90).ifBlank { "gambar" }
        return "$stem.$extension"
    }

    internal fun verifyFolder(file: DriveFileMetadata, expectedId: String) {
        if (expectedId !in ENTITY_TYPES.map(::folder) || file.id != expectedId || file.trashed ||
            file.mimeType != "application/vnd.google-apps.folder" || !file.canAddChildren || file.publicAccess ||
            file.ownerEmails.none { it.equals(OWNER_EMAIL, ignoreCase = true) }) {
            fail("failed-precondition", "Folder Drive perusahaan harus dimiliki $OWNER_EMAIL, privat, dan dibagikan sebagai Editor ke akun yang dipakai masuk.")
        }
    }

    internal fun verifyUpload(asset: NativeMediaAsset, fileId: String, type: String, entityId: String,
                              mime: String, bytes: ByteArray, nowMillis: Long = System.currentTimeMillis()) {
        registered(asset)
        if (asset.driveFileId != fileId || asset.entityType != type || asset.entityId != entityId ||
            asset.status !in setOf("reserved", "uploaded", "attached") || asset.expectedMime != mime ||
            asset.expectedBytes != bytes.size.toLong() || asset.originalSha256 != sha256(bytes) || !imageHeader(bytes, mime)) invalid()
        if (asset.status == "reserved" && asset.expiresAtMillis <= nowMillis) {
            fail("upload-session-expired", "Sesi unggah sudah berakhir. Pilih ulang gambar.")
        }
    }

    internal fun verifyRead(asset: NativeMediaAsset, type: String, expectedSha: String?) {
        registered(asset)
        if (asset.entityType != type || asset.status !in setOf("uploaded", "attached", "detached") ||
            expectedSha == null || !hashPattern.matches(expectedSha) || expectedSha != asset.sha256) invalid()
    }

    internal fun verifyDeletion(asset: NativeMediaAsset, type: String, expectedSha: String?) {
        registered(asset)
        if (asset.entityType != type || asset.status != "deleting" || expectedSha == null ||
            !hashPattern.matches(expectedSha) || expectedSha != asset.sha256) invalid()
    }

    internal fun verifyAssetFile(asset: NativeMediaAsset, file: DriveFileMetadata, allowTrashed: Boolean = false) {
        registered(asset)
        val mime = asset.mimeType.ifBlank { asset.expectedMime }
        val size = asset.sizeBytes.takeIf { it > 0 } ?: asset.expectedBytes
        val sha = asset.sha256.ifBlank { asset.originalSha256 }
        if (file.id != asset.driveFileId || file.trashed && !allowTrashed || file.mimeType != mime ||
            file.mimeType !in IMAGE_TYPES || asset.folder !in file.parents || file.sizeBytes != size ||
            size !in 1..MAX_IMAGE_BYTES.toLong() || !hashPattern.matches(sha) || file.publicAccess) invalid()
        // Private appProperties from another historical OAuth client may not be
        // visible. Registry, parent, size and downloaded content hash still apply.
        if (file.hasAppProperties && (file.application != "jayaguna-admin" || file.mediaId != asset.id ||
            file.entityId != asset.entityId || file.entityType != asset.entityType || file.sha256 != sha)) invalid()
    }

    internal fun verifyAssetBytes(asset: NativeMediaAsset, bytes: ByteArray) {
        registered(asset)
        val mime = asset.mimeType.ifBlank { asset.expectedMime }
        val size = asset.sizeBytes.takeIf { it > 0 } ?: asset.expectedBytes
        val sha = asset.sha256.ifBlank { asset.originalSha256 }
        if (bytes.size.toLong() != size || bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES ||
            sha256(bytes) != sha || !imageHeader(bytes, mime)) invalid()
    }

    private fun registered(asset: NativeMediaAsset) {
        assetId(asset.id); assetId(asset.entityId); fileId(asset.driveFileId)
        if (asset.folder != folder(asset.entityType) || asset.expectedMime !in IMAGE_TYPES ||
            asset.expectedBytes !in 1..MAX_IMAGE_BYTES.toLong() || !hashPattern.matches(asset.originalSha256)) invalid()
    }

    private fun invalid(): Nothing = fail("failed-precondition", "Berkas Drive tidak cocok dengan registrasi gambar perusahaan.")

    internal fun imageHeader(bytes: ByteArray, mime: String): Boolean = when (mime) {
        "image/png" -> bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)
        "image/jpeg" -> bytes.size >= 4 && bytes[0] == 255.toByte() && bytes[1] == 216.toByte() && bytes[2] == 255.toByte() && bytes[bytes.lastIndex] == 217.toByte()
        "image/webp" -> bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        else -> false
    }

    internal fun fail(code: String, message: String): Nothing = throw BackendException(code, message)
}
