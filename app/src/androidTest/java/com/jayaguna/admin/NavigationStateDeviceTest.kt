package com.jayaguna.admin

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.google.firebase.auth.FirebaseAuth
import com.jayaguna.admin.core.components.LazyListScrollPosition
import android.graphics.Bitmap
import org.json.JSONObject
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only device checks; opt in with navigationLive=true on an authenticated emulator. */
@RunWith(AndroidJUnit4::class)
class NavigationStateDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun requireExistingSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("navigationLive") == "true")
        assumeTrue(FirebaseAuth.getInstance().currentUser != null)
        waitFor("nav-products")
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun closeSoftKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }

    @Test fun filterAndPortfolioCategorySurviveTabSwitchAndActivityRecreation() {
        compose.onNodeWithTag("nav-products").performClick()
        waitFor("product-search")
        compose.onNodeWithTag("product-search").performScrollTo().performTextReplacement("State navigasi")
        closeSoftKeyboard()
        compose.onNodeWithTag("nav-portfolio").performClick()
        waitFor("portfolio-grid")
        compose.onNodeWithText("Perusahaan", substring = false).performClick()
        compose.waitUntil(45_000) { compose.onAllNodesWithText("Gambar perusahaan").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav-products").performClick()
        waitFor("product-search")
        compose.onNodeWithTag("product-search").assertTextContains("State navigasi")
        compose.activityRule.scenario.recreate()
        waitFor("product-search")
        compose.onNodeWithTag("nav-products").assertIsSelected()
        compose.onNodeWithTag("product-search").assertTextContains("State navigasi")
        compose.onNodeWithTag("product-search").performTextClearance()
        closeSoftKeyboard()
        compose.onNodeWithTag("nav-portfolio").performClick()
        waitFor("portfolio-grid")
        compose.onNodeWithText("Gambar perusahaan").assertExists()
    }

    @Test fun productDraftRestoresOnRecreationAndConfirmedDiscardClearsIt() {
        compose.onNodeWithTag("nav-products").performClick()
        waitFor("products-list")
        compose.onNodeWithText("+ Tambah produk").performScrollTo().performClick()
        compose.onNodeWithTag("product-name").performTextReplacement("Draft restorasi UI")
        compose.activityRule.scenario.recreate()
        waitFor("product-name")
        compose.onNodeWithTag("product-name").assertTextContains("Draft restorasi UI")
        closeSoftKeyboard()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Tinggalkan formulir?").assertExists()
        compose.onNodeWithText("Ya, lanjutkan").performClick()
        waitFor("products-list")
        compose.onNodeWithText("+ Tambah produk").performScrollTo().performClick()
        compose.onNodeWithTag("product-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    @Test fun accountReturnsToPreviousTabAndKeepsUnsavedDeviceName() {
        compose.onNodeWithTag("nav-products").performClick()
        waitFor("products-list")
        compose.onNodeWithContentDescription("Akun dan perangkat").performClick()
        waitFor("account-device-name")
        compose.onNodeWithTag("account-device-name").performScrollTo().performTextReplacement("Draft nama perangkat")
        closeSoftKeyboard()
        compose.onNodeWithTag("nav-partners").performClick()
        waitFor("partners-list")
        compose.onNodeWithContentDescription("Akun dan perangkat").performClick()
        compose.onNodeWithTag("account-device-name").performScrollTo().assertTextContains("Draft nama perangkat")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("nav-partners").assertIsSelected()
    }

    @Test fun productScrollSurvivesTabSwitchAndActivityRecreation() {
        compose.onNodeWithTag("nav-products").performClick()
        waitFor("products-list")
        compose.onNodeWithTag("products-list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        fun offset(): Float = compose.onNodeWithTag("products-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        fun position(): String = compose.onNodeWithTag("products-list").fetchSemanticsNode().config[LazyListScrollPosition]
        fun record(stage: String) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val list = compose.onNodeWithTag("products-list").fetchSemanticsNode()
            val focus = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty() }
            target.filesDir.resolve("navigation-scroll-$stage.json").writeText(JSONObject()
                .put("position", position()).put("offset", offset()).put("bounds", list.boundsInRoot.toString())
                .put("anchor", compose.onNodeWithText("Terapkan pencarian").getUnclippedBoundsInRoot().top.value)
                .put("focused", focus.toString()).toString())
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                target.filesDir.resolve("navigation-scroll-$stage.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val saved = offset()
        val savedPosition = position()
        record("before")
        assertTrue("The catalog must scroll for this device check", saved > 0f)
        compose.onNodeWithTag("nav-partners").performClick()
        waitFor("partners-list")
        compose.onNodeWithTag("nav-products").performClick()
        waitFor("products-list")
        assertEquals(savedPosition, position())
        compose.activityRule.scenario.recreate()
        waitFor("products-list")
        record("restored")
        assertEquals(savedPosition, position())
    }
}
