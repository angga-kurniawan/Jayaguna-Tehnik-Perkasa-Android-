package com.jayaguna.admin

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jayaguna.admin.core.components.SaveProgress
import com.jayaguna.admin.core.theme.JayagunaTheme
import com.jayaguna.admin.features.products.view.ProductsScreen
import com.jayaguna.admin.data.model.Product
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoadingStateUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialLoadDoesNotShowAnEmptyCatalogBeforeTheResult() {
        val ready = mutableStateOf(false)
        compose.setContent { JayagunaTheme {
            ProductsScreen(emptyList(), !ready.value, null, false, {}, {}, {}, {}, {}, initialReady = ready.value)
        } }
        compose.onNodeWithText("Produk AC").assertIsDisplayed()
        compose.onNodeWithTag("product-search").assertIsNotEnabled()
        compose.onNodeWithText("Memuat produk AC…").assertIsDisplayed()
        compose.onNodeWithText("Produk belum ditemukan").assertDoesNotExist()
        compose.onNodeWithTag("saving-progress").assertDoesNotExist()
        compose.runOnIdle { ready.value = true }
        compose.onNodeWithText("Memuat produk AC…").assertDoesNotExist()
        compose.onNodeWithText("Produk belum ditemukan").performScrollTo().assertIsDisplayed()
    }

    @Test fun uploadAtOneHundredPercentStillShowsPendingSaveConfirmation() {
        val progress = mutableStateOf(0.42f)
        compose.setContent { JayagunaTheme { SaveProgress(true, progress.value) } }
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.onNodeWithText("Mengunggah gambar…").assertIsDisplayed()
        compose.runOnIdle { progress.value = 1f }
        compose.onNodeWithText("Menyimpan perubahan…").assertIsDisplayed()
        compose.onNodeWithText("Menunggu konfirmasi penyimpanan.").assertIsDisplayed()
        compose.onNodeWithText("100%").assertDoesNotExist()
    }

    @Test fun refreshingAnInvalidatedListRetainsConfirmedRows() {
        compose.setContent { JayagunaTheme {
            ProductsScreen(listOf(Product(id = "confirmed", name = "Produk terkonfirmasi")), true, null, false,
                {}, {}, {}, {}, {}, initialReady = false, loading = true)
        } }
        compose.onNodeWithTag("products-list").assertExists()
        compose.onNodeWithTag("refresh-progress").assertIsDisplayed()
        compose.onNodeWithText("Memuat produk AC…").assertDoesNotExist()
        compose.onNodeWithTag("saving-progress").assertDoesNotExist()
        compose.onNodeWithText("Produk terkonfirmasi").performScrollTo().assertIsDisplayed()
    }
}
