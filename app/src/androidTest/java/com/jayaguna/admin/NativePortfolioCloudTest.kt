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
import com.jayaguna.admin.data.model.PortfolioImage
import com.jayaguna.admin.data.remote.portfolioImage
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

/** Opt-in cloud check using only the existing OWNER login and Google account grant. */
@RunWith(AndroidJUnit4::class)
class NativePortfolioCloudTest {
    @Test(timeout = 300_000) fun directDriveBothFoldersUploadReplaceRetryReadAndDelete() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativePortfolioLive") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AdminApplication
        val repository = requireNotNull(app.repository)
        val db = FirebaseFirestore.getInstance()
        val writer = NativeCatalogWriter(db, repository.auth)
        val drive = NativeDriveService(writer, requireNotNull(app.driveAuthorization))
        val actor = repository.session()
        assertEquals("OWNER", actor.role)
        assertEquals(NativeDrivePolicy.OWNER_EMAIL, actor.email)
        assertTrue(repository.driveStatus().connected)

        val entries = JSONArray(listOf("residential", "corporate").map { category ->
            JSONObject().put("id", UUID.randomUUID().toString()).put("category", category)
                .put("requests", JSONArray()).put("mediaIds", JSONArray())
        })
        val report = app.filesDir.resolve("native-portfolio-live-check.json")
        val localUris = mutableListOf<Uri>()
        fun record(stage: String, complete: Boolean = false) {
            report.writeText(JSONObject().put("portfolioEntries", entries).put("stage", stage)
                .put("completed", complete).toString())
        }
        fun request(entry: JSONObject): String = UUID.randomUUID().toString().also {
            entry.getJSONArray("requests").put(it)
            record("request-reserved")
        }
        fun image(color: Int): Pair<Uri, ByteArray> {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            val bytes = ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); out.toByteArray()
            }
            bitmap.recycle()
            val uri = requireNotNull(app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "jtp-portfolio-check-${UUID.randomUUID()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            }))
            localUris += uri
            app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return uri to bytes
        }
        record("authorized")
        try {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val id = entry.getString("id")
                val category = entry.getString("category")
                val type = PortfolioImage(category = category).entityType
                val folder = NativeDrivePolicy.folder(type)
                val ref = db.collection("portfolioImages").document(id)
                val original = image(if (index == 0) Color.RED else Color.GREEN)
                repository.savePortfolio(PortfolioImage(id, category, Media(storagePath = original.first.toString())), request(entry)) {}
                val first = ref.get(Source.SERVER).await().portfolioImage()
                val firstMedia = requireNotNull(first.image)
                entry.getJSONArray("mediaIds").put(firstMedia.storagePath.removePrefix("drive/"))
                record("$category-created")
                assertEquals(1L, first.version)
                assertTrue(repository.portfolio(category).items.any { it.id == id })
                assertArrayEquals(original.second, app.mediaRepository!!.bytes(firstMedia.storagePath))
                val metadata = drive.metadata(firstMedia.storagePath.removePrefix("drive/"))
                assertTrue(metadata.parents.contains(folder))
                assertTrue(metadata.ownerEmails.contains(NativeDrivePolicy.OWNER_EMAIL))
                val publicFirst = db.collection("publicPortfolioImages").document(id).get(Source.SERVER).await()
                assertEquals(category, publicFirst.getString("category"))
                assertNotNull(publicFirst.getTimestamp("createdAt"))

                val replacement = image(Color.BLUE)
                val changed = first.copy(image = Media(storagePath = replacement.first.toString()))
                val replaceRequest = request(entry)
                repository.savePortfolio(changed, replaceRequest) {}
                repository.savePortfolio(changed, replaceRequest) {} // Same request must not create a second version/file.
                val second = ref.get(Source.SERVER).await().portfolioImage()
                val secondMedia = requireNotNull(second.image)
                entry.getJSONArray("mediaIds").put(secondMedia.storagePath.removePrefix("drive/"))
                record("$category-replaced")
                assertEquals(2L, second.version)
                assertNotEquals(firstMedia.driveFileId, secondMedia.driveFileId)
                assertArrayEquals(replacement.second, app.mediaRepository!!.bytes(secondMedia.storagePath))
                assertEquals("deleted", db.collection("mediaAssets").document(firstMedia.storagePath.removePrefix("drive/")).get(Source.SERVER).await().getString("status"))
                assertEquals("deleted", writer.assetForRead(firstMedia.storagePath).status)

                val deleteRequest = request(entry)
                repository.deletePortfolio(second, deleteRequest)
                repository.deletePortfolio(second, deleteRequest)
                val archived = ref.get(Source.SERVER).await()
                assertEquals(true, archived.getBoolean("archived"))
                assertEquals(3L, archived.getLong("version"))
                assertNull(archived.get("image"))
                // Public rules intentionally deny reads of missing documents;
                // use the bounded public query to verify removal instead.
                assertTrue(db.collection("publicPortfolioImages").whereEqualTo("archived", false)
                    .whereEqualTo("id", id).limit(1).get(Source.SERVER).await().isEmpty)
                assertFalse(repository.portfolio(category).items.any { it.id == id })
                assertEquals("deleted", db.collection("mediaAssets").document(secondMedia.storagePath.removePrefix("drive/")).get(Source.SERVER).await().getString("status"))
                assertEquals("deleted", writer.assetForRead(secondMedia.storagePath).status)
                for (assetId in listOf(firstMedia.storagePath.removePrefix("drive/"), secondMedia.storagePath.removePrefix("drive/"))) {
                    for (prefix in listOf("upload_", "delete_")) {
                        val log = db.collection("audit_logs").document(prefix + assetId).get(Source.SERVER).await()
                        assertEquals("SUCCESS", log.getString("status"))
                        assertEquals(if (prefix == "upload_") "UPLOAD_PORTFOLIO_IMAGE" else "DELETE_PORTFOLIO_IMAGE", log.getString("action"))
                        assertEquals(actor.uid, log.getString("userId"))
                        assertNotNull(log.getTimestamp("timestamp"))
                    }
                }
                record("$category-verified")
            }
            record("verified", true)
        } finally {
            localUris.forEach { uri -> runCatching { app.contentResolver.delete(uri, null, null) } }
        }
    }
}
