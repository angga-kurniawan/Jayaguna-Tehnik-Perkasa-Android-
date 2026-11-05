package com.jayaguna.admin

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jayaguna.admin.core.components.AnimatedCatalogPage
import com.jayaguna.admin.core.theme.JayagunaTheme
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class AppMotionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidPageChangesKeepOneActivePageAndPreserveInput() {
        val page = mutableStateOf("products")
        compose.setContent { JayagunaTheme {
            val states = rememberSaveableStateHolder()
            AnimatedCatalogPage(page.value) { displayedPage ->
                states.SaveableStateProvider(displayedPage) {
                    var text by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(text, { text = it }, modifier = Modifier.testTag("draft-$displayedPage"))
                }
            }
        } }
        compose.onNodeWithTag("draft-products").performTextReplacement("Draft dipertahankan")
        compose.mainClock.autoAdvance = false
        for (destination in listOf("partners", "products", "portfolio", "products")) {
            compose.runOnIdle { page.value = destination }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onAllNodesWithTag("catalog-page").assertCountEquals(1)
        compose.onNodeWithTag("draft-products").assertTextContains("Draft dipertahankan")
        compose.onNodeWithTag("draft-partners").assertDoesNotExist()
        compose.onNodeWithTag("draft-portfolio").assertDoesNotExist()
    }

    @Test fun higherTabSlidesLeftAndLowerTabSlidesRight() {
        val page = mutableStateOf("products")
        compose.setContent { JayagunaTheme {
            AnimatedCatalogPage(page.value) { displayedPage ->
                Text(displayedPage, Modifier.fillMaxSize().testTag("page-body-$displayedPage"))
            }
        } }
        val start = compose.onNodeWithTag("catalog-page:products").getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page.value = "partners" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        val incomingLeft = compose.onNodeWithTag("catalog-page:partners").getUnclippedBoundsInRoot()
        val outgoingLeft = compose.onNodeWithTag("catalog-page:products").getUnclippedBoundsInRoot()
        assertTrue("Incoming next tab must move left from the right", incomingLeft.left > start.left)
        assertTrue("Outgoing tab must slide left", outgoingLeft.left < start.left)
        assertEquals(start.top.value, incomingLeft.top.value, 0.5f)
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { page.value = "products" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        val incomingRight = compose.onNodeWithTag("catalog-page:products").getUnclippedBoundsInRoot()
        val outgoingRight = compose.onNodeWithTag("catalog-page:partners").getUnclippedBoundsInRoot()
        assertTrue("Incoming previous tab must move right from the left", incomingRight.left < start.left)
        assertTrue("Outgoing tab must slide right", outgoingRight.left > start.left)
        assertEquals(start.top.value, incomingRight.top.value, 0.5f)
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("page-body-products").assertIsDisplayed()
        compose.onNodeWithTag("page-body-partners").assertDoesNotExist()
    }

    @Test fun interruptedSlideUsesTheLastSelectedTabForDirection() {
        val page = mutableStateOf("dashboard")
        compose.setContent { JayagunaTheme {
            AnimatedCatalogPage(page.value) { displayedPage -> Text(displayedPage, Modifier.fillMaxSize()) }
        } }
        val start = compose.onNodeWithTag("catalog-page:dashboard").getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page.value = "activity" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { page.value = "products" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        val incoming = compose.onNodeWithTag("catalog-page:products").getUnclippedBoundsInRoot()
        assertTrue("Riwayat to Produk must enter from the left even before Beranda to Riwayat finishes", incoming.left < start.left)
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("catalog-page:products").assertIsDisplayed()
        compose.onNodeWithTag("catalog-page:activity").assertDoesNotExist()
    }

    @Test fun quickReturnToTheOriginReversesTheSlideWithoutJumping() {
        val page = mutableStateOf("products")
        compose.setContent { JayagunaTheme {
            AnimatedCatalogPage(page.value) { displayedPage -> Text(displayedPage, Modifier.fillMaxSize()) }
        } }
        val start = compose.onNodeWithTag("catalog-page:products").getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page.value = "partners" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(96)
        val beforeReturn = compose.onNodeWithTag("catalog-page:products").getUnclippedBoundsInRoot()
        assertTrue(beforeReturn.left < start.left)
        compose.runOnIdle { page.value = "products" }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        val returning = compose.onNodeWithTag("catalog-page:products").getUnclippedBoundsInRoot()
        assertTrue("The returning page must still be sliding, not jump to its final position", returning.left < start.left)
        // Interrupted Compose slides retain velocity briefly. Check that the
        // page reverses after that momentum rather than requiring an instant snap.
        compose.mainClock.advanceTimeBy(128)
        val reversed = compose.onNodeWithTag("catalog-page:products").getUnclippedBoundsInRoot()
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir.resolve("tab-slide-reversal.json")
            .writeText(JSONObject().put("before", beforeReturn.left.value).put("after32ms", returning.left.value)
                .put("after160ms", reversed.left.value).put("origin", start.left.value).toString())
        assertTrue("The page must reverse toward the right", reversed.left > beforeReturn.left)
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("catalog-page:products").assertIsDisplayed()
        compose.onNodeWithTag("catalog-page:partners").assertDoesNotExist()
    }
}
