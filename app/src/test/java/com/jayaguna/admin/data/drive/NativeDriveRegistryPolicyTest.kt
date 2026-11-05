package com.jayaguna.admin.data.drive

import com.jayaguna.admin.core.security.BackendException
import com.jayaguna.admin.data.repository.NativeMediaAsset
import org.junit.Assert.*
import org.junit.Test

class NativeDriveRegistryPolicyTest {
    private val bytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
    private val sha = NativeDrivePolicy.sha256(bytes)
    private val id = "ea6ace01-34b3-4eef-b39f-f18af3216eef"
    private val entityId = "d7ff5031-a689-440f-bb52-aec58cd34419"
    private val asset = NativeMediaAsset(id, "registered_drive_file", "product", entityId, NativeDrivePolicy.PRODUCTS_FOLDER,
        "attached", "image/png", bytes.size.toLong(), sha, sha, "image/png", "test.png", bytes.size.toLong(), 20_000)
    private val file = DriveFileMetadata(asset.driveFileId, "test.png", "image/png", bytes.size.toLong(), listOf(asset.folder),
        listOf(NativeDrivePolicy.OWNER_EMAIL), sha, id, entityId, "product", false, false, false,
        NativeDrivePolicy.md5(bytes), true, "jayaguna-admin", true)
    private fun rejected(block: () -> Unit) = assertThrows(BackendException::class.java, block)

    @Test fun folderMustBeAnExistingPrivateWritableCompanyFolder() {
        val folder = file.copy(id = asset.folder, mimeType = "application/vnd.google-apps.folder", canAddChildren = true)
        NativeDrivePolicy.verifyFolder(folder, asset.folder)
        for (bad in listOf(folder.copy(trashed = true), folder.copy(canAddChildren = false), folder.copy(publicAccess = true),
            folder.copy(ownerEmails = listOf("other@example.com")), folder.copy(mimeType = "image/png"), folder.copy(id = "another_drive_folder"))) {
            rejected { NativeDrivePolicy.verifyFolder(bad, asset.folder) }
        }
    }

    @Test fun fileMustMatchRegistryIdentityFolderSizeAndPrivateAccess() {
        NativeDrivePolicy.verifyAssetFile(asset, file)
        for (bad in listOf(file.copy(id = "another_drive_file"), file.copy(parents = listOf(NativeDrivePolicy.PARTNERS_FOLDER)),
            file.copy(mimeType = "image/jpeg"), file.copy(sizeBytes = file.sizeBytes + 1), file.copy(publicAccess = true), file.copy(trashed = true))) {
            rejected { NativeDrivePolicy.verifyAssetFile(asset, bad) }
        }
    }

    @Test fun visibleApplicationPropertiesMustMatchEveryRegistryField() {
        for (bad in listOf(file.copy(application = "another-app"), file.copy(mediaId = entityId), file.copy(entityId = id),
            file.copy(entityType = "partner"), file.copy(sha256 = "0".repeat(64)))) {
            rejected { NativeDrivePolicy.verifyAssetFile(asset, bad) }
        }
    }

    @Test fun historicalClientPropertiesCanBeAbsentButContentVerificationIsStillMandatory() {
        NativeDrivePolicy.verifyAssetFile(asset, file.copy(hasAppProperties = false, application = "", sha256 = "", mediaId = "", entityId = "", entityType = ""))
        NativeDrivePolicy.verifyAssetBytes(asset, bytes)
        rejected { NativeDrivePolicy.verifyAssetBytes(asset, bytes.copyOf().apply { this[lastIndex] = 4 }) }
        rejected { NativeDrivePolicy.verifyAssetBytes(asset, bytes + 4) }
    }

    @Test fun readRejectsWrongTypeHashOrLifecycleState() {
        NativeDrivePolicy.verifyRead(asset, "product", sha)
        rejected { NativeDrivePolicy.verifyRead(asset, "partner", sha) }
        rejected { NativeDrivePolicy.verifyRead(asset, "product", "0".repeat(64)) }
        rejected { NativeDrivePolicy.verifyRead(asset, "product", null) }
        for (state in listOf("reserved", "deleting", "deleted", "")) {
            rejected { NativeDrivePolicy.verifyRead(asset.copy(status = state), "product", sha) }
        }
    }

    @Test fun deletionRequiresDeletingRegistryAndExplicitTrashAllowance() {
        rejected { NativeDrivePolicy.verifyDeletion(asset, "product", sha) }
        val deleting = asset.copy(status = "deleting")
        NativeDrivePolicy.verifyDeletion(deleting, "product", sha)
        rejected { NativeDrivePolicy.verifyDeletion(deleting, "partner", sha) }
        rejected { NativeDrivePolicy.verifyDeletion(deleting, "product", "0".repeat(64)) }
        NativeDrivePolicy.verifyAssetFile(deleting, file.copy(trashed = true), allowTrashed = true)
        rejected { NativeDrivePolicy.verifyAssetFile(deleting, file.copy(trashed = true)) }
    }

    @Test fun uploadRetryMustUseReservedIdentityAndExactlyTheSameBytes() {
        val reserved = asset.copy(status = "reserved", sha256 = "", mimeType = "", fileName = "", sizeBytes = 0)
        fun upload(value: NativeMediaAsset, content: ByteArray = bytes, now: Long = 1_000) =
            NativeDrivePolicy.verifyUpload(value, asset.driveFileId, "product", entityId, "image/png", content, now)
        upload(reserved)
        upload(asset) // committed upload may be checked again after an uncertain reply
        rejected { upload(reserved.copy(driveFileId = "different_drive_file")) }
        rejected { upload(reserved.copy(entityId = id)) }
        rejected { upload(reserved.copy(folder = NativeDrivePolicy.PARTNERS_FOLDER)) }
        rejected { upload(reserved.copy(status = "deleting")) }
        rejected { upload(reserved, bytes.copyOf().apply { this[lastIndex] = 4 }) }
        assertEquals("upload-session-expired", rejected { upload(reserved, now = 20_000) }.code)
    }

    @Test fun fileNameCannotInjectMultipartHeadersAndUsesMimeExtension() {
        assertEquals("logo.png", NativeDrivePolicy.storedFileName("logo.exe", "image/png"))
        assertEquals("logo.jpg", NativeDrivePolicy.storedFileName("logo", "image/jpeg"))
        assertThrows(IllegalArgumentException::class.java) { NativeDrivePolicy.storedFileName("logo\r\nheader: value", "image/png") }
        assertThrows(IllegalArgumentException::class.java) { NativeDrivePolicy.storedFileName("", "image/png") }
        rejected { NativeDrivePolicy.storedFileName("logo", "image/svg+xml") }
    }
}
