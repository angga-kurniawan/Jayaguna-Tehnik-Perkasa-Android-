package com.jayaguna.admin

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jayaguna.admin.core.components.StorageImage
import com.jayaguna.admin.core.theme.JayagunaTheme
import com.jayaguna.admin.data.model.Media
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImagePreviewUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var uri: Uri

    @Before fun createLocalImage() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "jtp-preview-check-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }))
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    @After fun cleanupLocalImage() {
        if (::uri.isInitialized) InstrumentationRegistry.getInstrumentation().targetContext.contentResolver.delete(uri, null, null)
    }
    private fun waitReady() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-ready:$uri").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun zoom(): Int = compose.onNodeWithTag("image-preview-viewport").fetchSemanticsNode()
        .config[SemanticsProperties.StateDescription].removePrefix("Zoom ").removeSuffix("%").toInt()

    @Test fun tappingLocalImageSupportsZoomGesturesCloseAndBack() {
        compose.setContent { JayagunaTheme { StorageImage(Media(storagePath = uri.toString()), "Gambar lokal", Modifier.size(160.dp)) } }
        waitReady()
        compose.onNodeWithTag("media-ready:$uri").performClick()
        compose.onNodeWithTag("image-preview").assertIsDisplayed()
        assertEquals(100, zoom())
        compose.onNodeWithTag("image-preview-zoom-out").assertIsNotEnabled()
        compose.onNodeWithTag("image-preview-zoom-in").performClick()
        assertEquals(150, zoom())
        compose.onNodeWithTag("image-preview-zoom-reset").performClick()
        compose.onNodeWithTag("image-preview-viewport").performTouchInput { doubleClick(center) }
        assertEquals(200, zoom())
        compose.onNodeWithTag("image-preview-viewport").performTouchInput { doubleClick(center) }
        assertEquals(100, zoom())
        compose.onNodeWithTag("image-preview-viewport").performTouchInput {
            val origin = center
            down(0, origin - Offset(40f, 0f)); down(1, origin + Offset(40f, 0f))
            moveTo(0, origin - Offset(160f, 0f)); moveTo(1, origin + Offset(160f, 0f))
            up(0); up(1)
        }
        assertTrue(zoom() in 101..400)
        compose.onAllNodesWithTag("image-preview").assertCountEquals(1)
        compose.onNodeWithTag("image-preview-close").performClick()
        compose.onNodeWithTag("image-preview").assertDoesNotExist()
        compose.onNodeWithTag("media-ready:$uri").performClick()
        assertEquals(100, zoom())
        pressBack()
        compose.onNodeWithTag("image-preview").assertDoesNotExist()
    }

    @Test fun openPreviewAndZoomSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { JayagunaTheme { StorageImage(Media(storagePath = uri.toString()), "Gambar tersimpan", Modifier.size(160.dp)) } }
        waitReady()
        compose.onNodeWithTag("media-ready:$uri").performClick()
        compose.onNodeWithTag("image-preview-zoom-in").performClick()
        assertEquals(150, zoom())
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("image-preview").assertIsDisplayed()
        assertEquals(150, zoom())
        compose.onNodeWithTag("image-preview-close").performClick()
    }
}
