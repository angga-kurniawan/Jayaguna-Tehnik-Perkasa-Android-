package com.jayaguna.admin

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jayaguna.admin.core.components.LazyListScrollPosition
import com.jayaguna.admin.core.components.LoadingFrame
import com.jayaguna.admin.core.theme.JayagunaTheme
import com.jayaguna.admin.data.model.AuditEntry
import com.jayaguna.admin.data.model.Product
import com.jayaguna.admin.features.activity.view.ActivityScreen
import com.jayaguna.admin.features.partners.view.PartnersScreen
import com.jayaguna.admin.features.products.view.ProductsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Fixture-only loading checks: no server, account, or catalog changes. */
@RunWith(AndroidJUnit4::class)
class LayoutLoadingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activityInitialLoadingKeepsThePageLayoutAndUsesRowPlaceholders() {
        compose.setContent { JayagunaTheme {
            ActivityScreen(emptyList(), true, null, false, {}, {}, { _, _, _, _, _ -> },
                owner = true, initialReady = false)
        } }
        compose.onNodeWithText("Riwayat aktivitas").assertIsDisplayed()
        compose.onNodeWithText("Filter riwayat").assertIsDisplayed()
        compose.onNodeWithText("Perbarui").assertIsNotEnabled()
        compose.onNodeWithText("Memuat riwayat aktivitas…").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("activity-loading-placeholder").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Belum ada aktivitas").assertDoesNotExist()
        compose.onNodeWithTag("saving-progress").assertDoesNotExist()
        screenshot("activity-loading-layout.png")
    }

    @Test fun refreshingActivityKeepsConfirmedEntriesWithOneThinProgressIndicator() {
        val entry = AuditEntry(id = "confirmed", actorName = "Admin terkonfirmasi", actorRole = "ADMIN",
            action = "edit", entityType = "partner", timestamp = "4 Nov 2026, 18:00")
        compose.setContent { JayagunaTheme {
            ActivityScreen(listOf(entry), true, null, false, {}, {}, { _, _, _, _, _ -> }, initialReady = false)
        } }
        compose.onNodeWithText("Admin terkonfirmasi · ADMIN").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("refresh-progress").assertCountEquals(1)
        compose.onNodeWithTag("refresh-progress").assertIsDisplayed()
        compose.onAllNodesWithTag("activity-loading-placeholder").assertCountEquals(0)
        compose.onNodeWithText("Memuat riwayat aktivitas…").assertDoesNotExist()
        compose.onNodeWithTag("saving-progress").assertDoesNotExist()
    }

    @Test fun partnerPlaceholdersUseTheSameTwoColumnPhoneGridAsTheLoadedCards() {
        compose.setContent { JayagunaTheme {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.width(360.dp).fillMaxHeight()) {
                    PartnersScreen(emptyList(), true, null, false, {}, {}, {}, {}, {}, initialReady = false)
                }
            }
        } }
        compose.onNodeWithText("Perusahaan mitra").assertIsDisplayed()
        val placeholders = compose.onAllNodesWithTag("partners-loading-placeholder").fetchSemanticsNodes()
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        assertTrue("Initial loading should populate the actual grid", placeholders.size >= 2)
        val left = placeholders[0].boundsInRoot
        val right = placeholders[1].boundsInRoot
        assertTrue("First two placeholders must share a row", abs(left.top - right.top) < 1f)
        assertTrue("Phone placeholders must occupy separate columns", left.right < right.left)
        compose.onNodeWithText("Belum ada mitra").assertDoesNotExist()
        compose.onNodeWithTag("saving-progress").assertDoesNotExist()
        screenshot("partners-loading-layout.png")
    }

    @Test fun savingProgressIsBoundedAndShowsItsRealPercentage() {
        compose.setContent { JayagunaTheme {
            LoadingFrame(true, "Menyimpan mitra…", "Tunggu hingga perubahan dikonfirmasi.", progress = 0.42f) {
                Text("Formulir mitra")
            }
        } }
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.onNodeWithText("Menyimpan mitra…").assertIsDisplayed()
        val dialog = compose.onNodeWithTag("saving-progress")
        dialog.assertIsDisplayed()
        val bounds = dialog.getUnclippedBoundsInRoot()
        assertTrue("Saving feedback must not stretch across the tablet", (bounds.right - bounds.left).value <= 420.5f)
        compose.onNodeWithTag("refresh-progress").assertDoesNotExist()
        screenshot("saving-loading-layout.png")
    }

    @Test fun aRestoredCatalogAnchorSurvivesAnEmptyLoadingPlaceholderList() {
        val fixtures = List(30) { index -> Product(id = "product-$index", name = "Produk $index") }
        val items = mutableStateOf(fixtures)
        val fetching = mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { JayagunaTheme {
            ProductsScreen(items.value, fetching.value, null, false, {}, {}, {}, {}, {},
                initialReady = !fetching.value, loading = fetching.value)
        } }
        compose.onNodeWithTag("products-list").performScrollToIndex(20)
        compose.waitForIdle()
        val saved = catalogPosition()
        assertTrue("The fixture must establish a real catalog anchor", saved.substringBefore(':').toInt() > 10)
        compose.runOnIdle { items.value = emptyList(); fetching.value = true }
        compose.onNodeWithText("Memuat produk AC…").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Memuat produk AC…").assertIsDisplayed()
        compose.runOnIdle { items.value = fixtures; fetching.value = false }
        compose.waitForIdle()
        assertEquals("The short skeleton list must not clamp the catalog's saved position", saved, catalogPosition())
    }

    private fun catalogPosition(): String = compose.onNodeWithTag("products-list").fetchSemanticsNode()
        .config[LazyListScrollPosition]

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            instrumentation.targetContext.filesDir.resolve(name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
