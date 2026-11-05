package com.jayaguna.admin

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jayaguna.admin.core.theme.JayagunaTheme
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Partner
import com.jayaguna.admin.features.partners.controller.PartnerBatchState
import com.jayaguna.admin.features.partners.controller.PendingPartnerDraft
import com.jayaguna.admin.features.partners.view.PartnerBatchScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Draft-only fixtures: no system picker, Firebase write, or Drive upload is performed. */
@RunWith(AndroidJUnit4::class)
class PartnerBatchUiTest {
    @get:Rule val compose = createComposeRule()

    private fun draft(id: String, locked: Boolean = false) = PendingPartnerDraft(
        partner = Partner(id = id, name = "Perusahaan $id", altText = "Logo Perusahaan $id",
            logo = Media(storagePath = "content://partner-batch-fixture/$id")),
        requestId = "request-$id", locked = locked,
    )

    private fun scrollTo(tag: String) = compose.onNodeWithTag("partner-batch-grid")
        .performScrollToNode(hasTestTag(tag))

    @Test fun eachSelectedLogoHasItsOwnEditableNameAndSaveAction() {
        val state = mutableStateOf(PartnerBatchState(selected = listOf(draft("first"), draft("second"))))
        var saves = 0
        compose.setContent { JayagunaTheme {
            PartnerBatchScreen(state.value, {}, { changed ->
                state.value = state.value.copy(selected = state.value.selected.map {
                    if (it.partner.id == changed.id) it.copy(partner = changed) else it
                })
            }, {}, {}, { saves++ }, {})
        } }
        scrollTo("partner-batch-name:first")
        compose.onNodeWithTag("partner-batch-name:first").performTextReplacement("Mitra Baru")
        compose.runOnIdle {
            assertEquals("Mitra Baru", state.value.selected[0].partner.name)
            assertEquals("Logo Mitra Baru", state.value.selected[0].partner.altText)
            assertEquals("Perusahaan second", state.value.selected[1].partner.name)
        }
        scrollTo("partner-batch-save")
        compose.onNodeWithTag("partner-batch-save").assertTextContains("Simpan 2 mitra").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun removalTargetsOnlyTheChosenLogo() {
        val state = mutableStateOf(PartnerBatchState(selected = listOf(draft("first"), draft("second"))))
        val removed = mutableListOf<String>()
        compose.setContent { JayagunaTheme {
            PartnerBatchScreen(state.value, {}, {}, { id ->
                removed += id
                state.value = state.value.copy(selected = state.value.selected.filterNot { it.partner.id == id })
            }, {}, {}, {})
        } }
        scrollTo("partner-batch-remove:second")
        compose.onNodeWithTag("partner-batch-remove:second").performClick()
        compose.runOnIdle { assertEquals(listOf("second"), removed) }
        compose.onNodeWithTag("partner-batch-card:second").assertDoesNotExist()
        scrollTo("partner-batch-card:first")
        compose.onNodeWithTag("partner-batch-card:first").assertIsDisplayed()
    }

    @Test fun uncertainDraftsAreReadOnlyButTheRemainingQueueCanRetry() {
        var saves = 0
        compose.setContent { JayagunaTheme {
            PartnerBatchScreen(PartnerBatchState(selected = listOf(draft("locked", locked = true), draft("new")), canRetry = true),
                {}, {}, {}, {}, { saves++ }, {})
        } }
        scrollTo("partner-batch-name:locked")
        compose.onNodeWithTag("partner-batch-name:locked").assertIsNotEnabled()
        scrollTo("partner-batch-remove:locked")
        compose.onNodeWithTag("partner-batch-remove:locked").assertIsNotEnabled()
        scrollTo("partner-batch-name:new")
        compose.onNodeWithTag("partner-batch-name:new").assertIsEnabled()
        scrollTo("partner-batch-save")
        compose.onNodeWithTag("partner-batch-save").assertTextContains("Coba simpan 2 mitra lagi").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun savingBlocksChoosingEditingRemovingAndLeaving() {
        val state = mutableStateOf(PartnerBatchState(selected = listOf(draft("one"))))
        var leaves = 0
        compose.setContent { JayagunaTheme {
            PartnerBatchScreen(state.value, {}, {}, {}, {}, {}, { leaves++ })
        } }
        scrollTo("partner-batch-name:one")
        compose.runOnIdle { state.value = state.value.copy(saving = true, progress = .4f, operation = "Mengunggah logo 1 dari 1") }
        compose.onNodeWithTag("saving-progress").assertIsDisplayed()
        compose.onNodeWithTag("partner-batch-pick").assertIsNotEnabled()
        compose.onNodeWithTag("partner-batch-name:one").assertIsNotEnabled()
        compose.onNodeWithTag("partner-batch-remove:one").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, leaves) }
    }

    @Test fun leavingWithDraftsNeedsConfirmationAndPreservesInputWhenCancelled() {
        val events = mutableListOf<String>()
        compose.setContent { JayagunaTheme {
            PartnerBatchScreen(PartnerBatchState(selected = listOf(draft("one"))), {}, {}, {},
                { events += "discard" }, {}, { events += "cancel" })
        } }
        scrollTo("partner-batch-back")
        compose.onNodeWithTag("partner-batch-back").performClick()
        compose.onNodeWithTag("partner-batch-discard-dialog").assertIsDisplayed()
        compose.onNodeWithTag("partner-batch-keep").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.onNodeWithTag("partner-batch-back").performClick()
        compose.onNodeWithTag("partner-batch-confirm-discard").performClick()
        compose.runOnIdle { assertEquals(listOf("discard", "cancel"), events) }
    }

    @Test fun discardingAnUncertainQueueExplainsThatSomeResultsNeedChecking() {
        val events = mutableListOf<String>()
        compose.setContent { JayagunaTheme {
            PartnerBatchScreen(PartnerBatchState(selected = listOf(draft("one", locked = true))), {}, {}, {},
                { events += "discard" }, {}, { events += "cancel" })
        } }
        scrollTo("partner-batch-discard")
        compose.onNodeWithTag("partner-batch-discard").performClick()
        compose.onNodeWithText("Pilihan yang tersisa akan dibuang. Logo yang sudah berhasil disimpan tetap ada. Ada hasil penyimpanan yang belum dikonfirmasi; periksa daftar mitra sebelum menambah logo yang sama lagi.").assertIsDisplayed()
        compose.onNodeWithTag("partner-batch-confirm-discard").performClick()
        compose.runOnIdle { assertEquals(listOf("discard"), events) }
    }

    @Test fun invalidDisplayOrderDoesNotSubmitTheBatch() {
        val state = mutableStateOf(PartnerBatchState(selected = listOf(draft("one"))))
        var saves = 0
        compose.setContent { JayagunaTheme {
            PartnerBatchScreen(state.value, {}, { changed ->
                state.value = state.value.copy(selected = listOf(state.value.selected.single().copy(partner = changed)))
            }, {}, {}, { saves++ }, {})
        } }
        scrollTo("partner-batch-details:one")
        compose.onNodeWithTag("partner-batch-details:one").performClick()
        scrollTo("partner-batch-order:one")
        compose.onNodeWithTag("partner-batch-order:one").performTextReplacement("-1")
        scrollTo("partner-batch-save")
        compose.onNodeWithTag("partner-batch-save").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun twoLocalLogoPreviewsRenderAlongsideTheirOwnNames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val temporary = mutableListOf<Uri>()
        fun image(color: Int): Uri {
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "jtp-batch-preview-${UUID.randomUUID()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            }))
            temporary += uri
            val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            resolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            return uri
        }
        try {
            val blue = image(Color.rgb(17, 38, 93))
            val red = image(Color.rgb(195, 38, 57))
            val items = listOf(draft("Biru"), draft("Merah")).mapIndexed { index, pending ->
                pending.copy(partner = pending.partner.copy(logo = Media(storagePath = listOf(blue, red)[index].toString())))
            }
            compose.setContent { JayagunaTheme {
                Box(Modifier.width(600.dp).fillMaxHeight()) {
                    PartnerBatchScreen(PartnerBatchState(selected = items), {}, {}, {}, {}, {}, {})
                }
            } }
            for (uri in listOf(blue, red)) {
                val id = if (uri == blue) "Biru" else "Merah"
                scrollTo("partner-batch-card:$id")
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("media-ready:$uri").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("media-ready:$uri").assertIsDisplayed()
                compose.onNodeWithTag("partner-batch-name:$id").assertTextContains("Perusahaan $id")
            }
            scrollTo("partner-batch-card:Biru")
            compose.waitForIdle()
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                context.filesDir.resolve("partner-batch-preview.png").outputStream().use {
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                screenshot.recycle()
            }
        } finally {
            temporary.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        }
    }
}
