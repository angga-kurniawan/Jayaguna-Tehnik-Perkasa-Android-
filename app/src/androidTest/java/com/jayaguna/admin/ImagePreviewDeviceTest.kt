package com.jayaguna.admin

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only verification of the real Drive preview and shared cache. */
@RunWith(AndroidJUnit4::class)
class ImagePreviewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(60_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }

    @Test(timeout = 180_000) fun drivePreviewReusesCacheRestoresZoomAndClosesToTheSamePage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("imagePreviewLive") == "true")
        assumeTrue(FirebaseAuth.getInstance().currentUser != null)
        waitFor("nav-products")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AdminApplication
        val repository = requireNotNull(app.repository)
        val media = requireNotNull(app.mediaRepository)
        val candidate = runBlocking { repository.products().items.firstNotNullOfOrNull { product ->
            val image = product.images.firstOrNull { it.storagePath == product.mainImagePath } ?: product.images.firstOrNull()
            image?.takeIf { it.storagePath.startsWith("drive/") }?.let { product to it }
        } }
        assumeTrue(candidate != null)
        val (product, image) = requireNotNull(candidate)
        val ready = "media-ready:${image.storagePath}"
        compose.onNodeWithTag("nav-products").performClick()
        waitFor("products-list")
        compose.onNodeWithTag("products-list").performScrollToNode(hasContentDescription(product.name))
        waitFor(ready)
        compose.waitUntil(60_000) { media.diagnostics().inFlight == 0 }
        val before = media.diagnostics().fetchesStarted
        compose.onNodeWithTag(ready).performClick()
        waitFor("image-preview")
        compose.onNodeWithTag("image-preview-viewport").assertIsDisplayed()
        compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag(ready) and hasAnyAncestor(hasTestTag("image-preview"))).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(60_000) { media.diagnostics().inFlight == 0 }
        assertEquals(before, media.diagnostics().fetchesStarted)
        compose.onNodeWithTag("image-preview-zoom-in").performClick()
        compose.onNodeWithTag("image-preview-viewport").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 150%"))
        compose.activityRule.scenario.recreate()
        waitFor("image-preview")
        compose.onNodeWithTag("image-preview-viewport").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 150%"))
        compose.onNodeWithTag("image-preview-close").performClick()
        compose.onNodeWithTag("nav-products").assertIsSelected()
        compose.onNodeWithTag(ready).assertIsDisplayed()
        compose.onNodeWithTag(ready).performClick()
        waitFor("image-preview")
        pressBack()
        compose.onNodeWithTag("image-preview").assertDoesNotExist()
        compose.onNodeWithTag("nav-products").assertIsSelected()
        assertEquals(before, media.diagnostics().fetchesStarted)
        app.filesDir.resolve("image-preview-live-check.json").writeText(JSONObject()
            .put("completed", true).put("previewFetchDelta", media.diagnostics().fetchesStarted - before)
            .put("zoomRestored", true).put("returnedToProducts", true).toString())
    }
}
