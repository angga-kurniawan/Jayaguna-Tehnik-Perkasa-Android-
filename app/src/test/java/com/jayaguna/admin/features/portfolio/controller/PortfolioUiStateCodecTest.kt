package com.jayaguna.admin.features.portfolio.controller

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PortfolioUiStateCodecTest {
    @Test fun corruptOrUnexpectedSnapshotsAreRejected() {
        val valid = PortfolioUiStateCodec.encode(RestoredPortfolioState("residential", emptyList(), null))
        assertNull(PortfolioUiStateCodec.decode(valid.dropLast(1)))
        assertNull(PortfolioUiStateCodec.decode(valid + "unexpected"))
        assertNull(PortfolioUiStateCodec.decode(valid.toMutableList().apply { this[1] = "other" }))
        assertNull(PortfolioUiStateCodec.decode(valid.toMutableList().apply { this[2] = "9" }))
    }

    @Test fun savedSelectionIsBoundedAndMustReferenceLocalPhotoPickerContent() {
        fun selection(uri: String) = PendingPortfolioImage(UUID.randomUUID().toString(), uri, UUID.randomUUID().toString())
        val photo = selection("content://photos/one")
        val valid = RestoredPortfolioState("corporate", listOf(photo), null)
        assertEquals(valid, PortfolioUiStateCodec.decode(PortfolioUiStateCodec.encode(valid)))
        assertNull(PortfolioUiStateCodec.decode(PortfolioUiStateCodec.encode(valid.copy(selected = listOf(selection("https://example.com/photo.png"))))))
        assertNull(PortfolioUiStateCodec.decode(PortfolioUiStateCodec.encode(valid.copy(selected = List(9) { selection("content://photos/$it") }))))
        assertNull(PortfolioUiStateCodec.decode(PortfolioUiStateCodec.encode(valid.copy(selected = listOf(photo, photo)))))
    }
}
