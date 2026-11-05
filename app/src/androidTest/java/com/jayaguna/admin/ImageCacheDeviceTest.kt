package com.jayaguna.admin

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.jayaguna.admin.data.drive.NativeDrivePolicy
import com.jayaguna.admin.core.components.catalogMemoryCacheKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only cloud check. No catalog, Drive file, role or login is changed. */
@RunWith(AndroidJUnit4::class)
class ImageCacheDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun requireExistingSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("imageCacheLive") == "true")
        assumeTrue(FirebaseAuth.getInstance().currentUser != null)
        waitFor("nav-products")
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test(timeout = 180_000) fun driveImageLoadsOnceThenUsesMemoryAndDiskAcrossNavigation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AdminApplication
        val repository = requireNotNull(app.repository)
        val media = requireNotNull(app.mediaRepository)
        val loader = requireNotNull(app.catalogImageLoader)
        compose.onNodeWithTag("nav-dashboard").performClick()
        waitFor("dashboard-scroll")
        val candidate = runBlocking {
            repository.products().items.firstNotNullOfOrNull { product ->
                val image = product.images.firstOrNull { it.storagePath == product.mainImagePath }
                    ?: product.images.firstOrNull()
                image?.takeIf { it.storagePath.startsWith("drive/") }?.let { product to it }
            }
        }
        assumeTrue("An existing Drive product image is required for the read-only cloud check.", candidate != null)
        val (product, image) = requireNotNull(candidate)
        val path = image.storagePath
        val ready = "media-ready:$path"
        val loading = "media-loading:$path"
        fun awaitImageReads() {
            compose.waitForIdle()
            compose.waitUntil(60_000) { media.diagnostics().inFlight == 0 }
            compose.waitForIdle()
        }
        val report = JSONObject().put("completed", false).put("productId", product.id)
        fun record(stage: String) {
            val stats = media.diagnostics()
            report.put("stage", stage).put("fetchesStarted", stats.fetchesStarted)
                .put("memoryHits", stats.memoryHits).put("diskHits", stats.diskHits)
                .put("coalescedReads", stats.coalescedReads)
            app.filesDir.resolve("image-cache-live-check.json").writeText(report.toString())
        }
        try {
            // A first visit to another tab may correctly download its different
            // images. Warm both destinations before measuring repeat visits.
            compose.onNodeWithTag("nav-partners").performClick()
            waitFor("partners-list")
            awaitImageReads()
            compose.onNodeWithTag("nav-products").performClick()
            waitFor("products-list")
            compose.onNodeWithTag("products-list").performScrollToNode(hasContentDescription(product.name))
            waitFor(ready)
            awaitImageReads()
            compose.onNodeWithTag("nav-dashboard").performClick()
            waitFor("dashboard-scroll")
            // Evict only this local copy to prove the first download, never the cloud file.
            media.invalidate(path)
            loader.memoryCache?.clear()
            val initial = media.diagnostics()
            val downloaded = runBlocking { coroutineScope { List(4) { async { media.bytes(path) } }.awaitAll() } }
            val bytes = downloaded.first()
            assertTrue(bytes.isNotEmpty())
            downloaded.forEach { assertArrayEquals(bytes, it) }
            assertEquals("Concurrent readers must share one remote load", initial.fetchesStarted + 1, media.diagnostics().fetchesStarted)
            report.put("controlledDownloadDelta", media.diagnostics().fetchesStarted - initial.fetchesStarted)
            report.put("firstDownloadSha256", NativeDrivePolicy.sha256(bytes))
            record("downloaded-once")

            assertArrayEquals(bytes, runBlocking { media.bytes(path) })
            val beforeDisk = media.diagnostics()
            media.clearMemoryCache()
            assertArrayEquals(bytes, runBlocking { media.bytes(path) })
            assertEquals(beforeDisk.fetchesStarted, media.diagnostics().fetchesStarted)
            assertTrue("Clearing RAM must still allow the private disk copy", media.diagnostics().diskHits > beforeDisk.diskHits)
            record("disk-hit-without-network")

            compose.onNodeWithTag("nav-products").performClick()
            waitFor("products-list")
            compose.onNodeWithTag("products-list").performScrollToNode(hasContentDescription(product.name))
            waitFor(ready)
            compose.onNodeWithTag(ready).assertIsDisplayed()
            awaitImageReads()
            val stableKey = catalogMemoryCacheKey(requireNotNull(media.cacheIdentity.value), path)
            assertTrue("A decoded image must be retained in the shared image loader",
                loader.memoryCache?.keys?.any { it.key == stableKey } == true)
            val beforeNavigation = media.diagnostics()
            compose.onNodeWithTag("nav-partners").performClick()
            waitFor("partners-list")
            awaitImageReads()
            compose.onNodeWithTag("nav-products").performClick()
            waitFor("products-list")
            compose.onNodeWithTag(ready).assertIsDisplayed()
            compose.onAllNodesWithTag(loading).assertCountEquals(0)
            assertEquals("Returning to the tab must not read Firestore/Drive again",
                beforeNavigation.fetchesStarted, media.diagnostics().fetchesStarted)
            report.put("navigationFetchDelta", media.diagnostics().fetchesStarted - beforeNavigation.fetchesStarted)
            record("navigation-hit-without-loading")

            compose.activityRule.scenario.recreate()
            waitFor(ready)
            compose.onNodeWithTag(ready).assertIsDisplayed()
            compose.onAllNodesWithTag(loading).assertCountEquals(0)
            assertEquals(beforeNavigation.fetchesStarted, media.diagnostics().fetchesStarted)
            record("activity-recreation-hit")

            // Decode and byte RAM may be evicted by Android; the private disk
            // copy should still prevent another Firestore/Drive request.
            compose.onNodeWithTag("nav-partners").performClick()
            waitFor("partners-list")
            loader.memoryCache?.clear()
            media.clearMemoryCache()
            val beforeDiskNavigation = media.diagnostics()
            compose.onNodeWithTag("nav-products").performClick()
            waitFor(ready)
            assertEquals(beforeDiskNavigation.fetchesStarted, media.diagnostics().fetchesStarted)
            assertTrue(media.diagnostics().diskHits > beforeDiskNavigation.diskHits)
            report.put("completed", true).put("diskNavigationFetchDelta",
                media.diagnostics().fetchesStarted - beforeDiskNavigation.fetchesStarted)
            record("verified")
        } finally {
            if (!report.optBoolean("completed")) record("failed")
        }
    }
}
