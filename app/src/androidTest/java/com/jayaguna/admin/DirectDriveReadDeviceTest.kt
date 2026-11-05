package com.jayaguna.admin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jayaguna.admin.data.drive.NativeDrivePolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: reads existing folders/image only; never requests consent or writes cloud data. */
@RunWith(AndroidJUnit4::class)
class DirectDriveReadDeviceTest {
    @Test(timeout = 120_000) fun approvedGoogleAccountReadsExistingImageWithoutBackend() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("directDriveReadLive") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AdminApplication
        val repository = requireNotNull(app.repository)
        val media = requireNotNull(app.mediaRepository)
        val report = JSONObject().put("completed", false).put("backendConfigured", BuildConfig.BACKEND_BASE_URL.isNotBlank())
        fun record(stage: String) {
            report.put("stage", stage)
            app.filesDir.resolve("direct-drive-read-check.json").writeText(report.toString())
        }
        try {
            withTimeout(100_000) {
                repository.session()
                val connection = repository.driveStatus()
                assertTrue(connection.connected)
                assertEquals(requireNotNull(app.driveAuthorization).currentEmail(), connection.accountEmail)
                report.put("foldersVerified", 4)
                record("folders-verified")
                val image = repository.products().items.asSequence().flatMap { it.images.asSequence() }
                    .firstOrNull { it.storagePath.startsWith("drive/") }
                assumeTrue("An existing Drive product image is required; cloud data is never created by this check.", image != null)
                val path = requireNotNull(image).storagePath
                media.invalidate(path)
                val before = media.diagnostics()
                val bytes = media.bytes(path)
                assertTrue(bytes.isNotEmpty())
                assertEquals(before.fetchesStarted + 1, media.diagnostics().fetchesStarted)
                assertArrayEquals(bytes, media.bytes(path))
                assertEquals(before.fetchesStarted + 1, media.diagnostics().fetchesStarted)
                report.put("networkReads", 1).put("sizeBytes", bytes.size)
                    .put("sha256", NativeDrivePolicy.sha256(bytes)).put("completed", true)
                record("verified")
            }
        } finally { if (!report.optBoolean("completed")) record("incomplete") }
    }
}
