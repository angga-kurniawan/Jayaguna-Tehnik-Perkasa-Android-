package com.jayaguna.admin

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.jayaguna.admin.data.drive.NativeDrivePolicy
import com.jayaguna.admin.data.drive.NativeDriveService
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Product
import com.jayaguna.admin.data.remote.product
import com.jayaguna.admin.data.repository.NativeCatalogWriter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Explicit opt-in only: uses an approved Google OWNER and an on-device Drive grant. */
@RunWith(AndroidJUnit4::class)
class NativeDriveCloudTest {
    @Test(timeout = 300_000) fun directDriveUploadReplaceReadAndCleanup() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeDriveLive") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AdminApplication
        val repository = requireNotNull(app.repository)
        val db = FirebaseFirestore.getInstance()
        val writer = NativeCatalogWriter(db, repository.auth)
        val drive = NativeDriveService(writer, requireNotNull(app.driveAuthorization))
        val account = repository.session()
        assertEquals("OWNER", account.role)
        assertEquals(NativeDrivePolicy.OWNER_EMAIL, account.email)
        assertTrue(repository.driveStatus().connected)
        val entityId = UUID.randomUUID().toString()
        val requests = mutableListOf<String>()
        val mediaIds = mutableListOf<String>()
        val localUris = mutableListOf<Uri>()
        val report = app.filesDir.resolve("native-drive-live-check.json")
        fun record(stage: String, completed: Boolean = false) {
            report.writeText(JSONObject().put("entityId", entityId).put("requests", JSONArray(requests))
                .put("mediaIds", JSONArray(mediaIds)).put("stage", stage).put("completed", completed).toString())
        }
        fun request(): String = UUID.randomUUID().toString().also { requests += it }
        fun image(color: Int): Pair<Uri, ByteArray> {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            val bytes = ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
            bitmap.recycle()
            val uri = requireNotNull(app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "jtp-check-${UUID.randomUUID()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            }))
            localUris += uri
            app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return uri to bytes
        }
        try {
            record("authorized")
            val first = image(Color.RED)
            val draft = Product(id = entityId, name = "Uji koneksi native $entityId", brand = "Pemeriksaan teknis", images = listOf(Media(storagePath = first.first.toString())), mainImagePath = first.first.toString())
            repository.saveProduct(draft, request()) {}
            val firstSaved = db.collection("products").document(entityId).get(Source.SERVER).await().product()
            val firstMedia = firstSaved.images.single()
            mediaIds += firstMedia.storagePath.removePrefix("drive/")
            record("first-upload-saved")
            assertEquals(1L, firstSaved.version)
            assertArrayEquals(first.second, app.mediaRepository!!.bytes(firstMedia.storagePath))
            assertTrue(drive.metadata(firstMedia.storagePath.removePrefix("drive/")).ownerEmails.contains(NativeDrivePolicy.OWNER_EMAIL))

            val replacement = image(Color.BLUE)
            val changed = firstSaved.copy(images = listOf(Media(storagePath = replacement.first.toString())), mainImagePath = replacement.first.toString())
            val replaceRequest = request()
            repository.saveProduct(changed, replaceRequest) {}
            repository.saveProduct(changed, replaceRequest) {} // same uncertain-outcome retry
            val secondSaved = db.collection("products").document(entityId).get(Source.SERVER).await().product()
            val secondMedia = secondSaved.images.single()
            mediaIds += secondMedia.storagePath.removePrefix("drive/")
            record("replacement-saved")
            assertEquals(2L, secondSaved.version)
            assertNotEquals(firstMedia.driveFileId, secondMedia.driveFileId)
            assertArrayEquals(replacement.second, app.mediaRepository!!.bytes(secondMedia.storagePath))
            assertEquals("deleted", db.collection("mediaAssets").document(firstMedia.storagePath.removePrefix("drive/")).get(Source.SERVER).await().getString("status"))
            assertEquals("deleted", writer.assetForRead(firstMedia.storagePath).status)

            repository.saveProduct(secondSaved.copy(images = emptyList(), mainImagePath = ""), request()) {}
            assertEquals("deleted", db.collection("mediaAssets").document(secondMedia.storagePath.removePrefix("drive/")).get(Source.SERVER).await().getString("status"))
            repository.archive("product", entityId, 3, request())
            assertTrue(db.collection("products").document(entityId).get(Source.SERVER).await().getBoolean("archived") == true)

            // An unattached logo proves the second folder without publishing a fake company.
            val logoRequest = request()
            val device = repository.device.info()
            val slot = writer.reserveMedia(logoRequest, device, "partner", UUID.randomUUID().toString(), "image/png", first.second.size.toLong(), NativeDrivePolicy.sha256(first.second), "uji-logo", drive.reserveFileId())
            mediaIds += slot.id
            record("logo-reserved")
            val logo = drive.upload(slot.driveFileId, slot.id, "partner", slot.entityId, "uji-logo", "image/png", first.second)
            writer.markUploaded(slot.id, logo, NativeDrivePolicy.sha256(first.second))
            assertArrayEquals(first.second, app.mediaRepository!!.bytes(logo.storagePath))
            assertTrue(drive.metadata(logo.storagePath.removePrefix("drive/")).parents.contains(NativeDrivePolicy.PARTNERS_FOLDER))
            val delete = request()
            writer.beginMediaDeletion(slot.id, delete, device)
            drive.trash(slot.id, "partner", NativeDrivePolicy.sha256(first.second))
            writer.finishMediaDeletion(slot.id, delete, device)
            assertEquals("deleted", writer.assetForRead(logo.storagePath).status)
            for (id in mediaIds) {
                assertEquals("SUCCESS", db.collection("audit_logs").document("upload_$id").get(Source.SERVER).await().getString("status"))
                assertEquals("SUCCESS", db.collection("audit_logs").document("delete_$id").get(Source.SERVER).await().getString("status"))
            }
            record("verified", true)
        } finally {
            localUris.forEach { uri -> runCatching { app.contentResolver.delete(uri, null, null) } }
        }
    }
}
