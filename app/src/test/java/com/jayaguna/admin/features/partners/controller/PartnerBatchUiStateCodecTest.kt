package com.jayaguna.admin.features.partners.controller

import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Partner
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PartnerBatchUiStateCodecTest {
    private fun draft(uri: String = "content://photos/${UUID.randomUUID()}") = PendingPartnerDraft(
        Partner(UUID.randomUUID().toString(), "Perusahaan", Media(storagePath = uri), "Logo perusahaan", "https://example.com", 42, false),
        UUID.randomUUID().toString(), true)

    @Test fun fullDraftAndFrozenRequestRoundTripWithoutNetworkState() {
        val selected = listOf(draft(), draft().copy(locked = false))
        assertEquals(selected, PartnerBatchUiStateCodec.decode(PartnerBatchUiStateCodec.encode(selected)))
        assertEquals(emptyList<PendingPartnerDraft>(), PartnerBatchUiStateCodec.decode(PartnerBatchUiStateCodec.encode(emptyList())))
    }

    @Test fun unfinishedEditableInputSurvivesRestoreForUserCorrection() {
        val pending = draft().let { it.copy(partner = it.partner.copy(name = "", altText = "", website = "https://", displayOrder = -1), locked = false) }
        assertEquals(listOf(pending), PartnerBatchUiStateCodec.decode(PartnerBatchUiStateCodec.encode(listOf(pending))))
    }

    @Test fun malformedTruncatedOversizedAndDuplicateSnapshotsAreRejected() {
        val pending = draft()
        val valid = PartnerBatchUiStateCodec.encode(listOf(pending))
        assertNull(PartnerBatchUiStateCodec.decode(valid.dropLast(1)))
        assertNull(PartnerBatchUiStateCodec.decode(valid + "extra"))
        assertNull(PartnerBatchUiStateCodec.decode(valid.toMutableList().apply { this[0] = "2" }))
        assertNull(PartnerBatchUiStateCodec.decode(PartnerBatchUiStateCodec.encode(List(9) { draft() })))
        assertNull(PartnerBatchUiStateCodec.decode(PartnerBatchUiStateCodec.encode(listOf(pending, pending))))
        assertNull(PartnerBatchUiStateCodec.decode(PartnerBatchUiStateCodec.encode(listOf(pending, draft().copy(requestId = pending.requestId)))))
        assertNull(PartnerBatchUiStateCodec.decode(PartnerBatchUiStateCodec.encode(listOf(pending, draft(pending.partner.logo!!.storagePath)))))
    }

    @Test fun savedLogoMustBeOnlyALocalPhotoPickerReference() {
        val original = draft()
        val badMedia = listOf(Media(storagePath = "https://example.com/logo.png"), Media(storagePath = "file:///tmp/logo.png"),
            Media(storagePath = "content://"), Media(storagePath = "content://photos/one", url = "https://secret.invalid"),
            Media(storagePath = "content://photos/one", sizeBytes = 100))
        badMedia.forEach { logo ->
            assertNull(PartnerBatchUiStateCodec.decode(PartnerBatchUiStateCodec.encode(listOf(original.copy(partner = original.partner.copy(logo = logo))))))
        }
    }

    @Test fun restoredDraftCannotTargetAnExistingOrArchivedPartner() {
        val pending = draft()
        for (partner in listOf(pending.partner.copy(version = 1), pending.partner.copy(archived = true),
            pending.partner.copy(id = "not-a-uuid"), pending.partner.copy(name = "x".repeat(161)), pending.partner.copy(altText = "x".repeat(251)))) {
            assertNull(PartnerBatchUiStateCodec.decode(PartnerBatchUiStateCodec.encode(listOf(pending.copy(partner = partner)))))
        }
    }
}
