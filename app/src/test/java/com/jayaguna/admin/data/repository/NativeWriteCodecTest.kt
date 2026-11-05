package com.jayaguna.admin.data.repository

import org.junit.Assert.*
import org.junit.Test

class NativeWriteCodecTest {
    @Test fun searchIncludesEveryWordPrefixAndBoundsLongNames() {
        val tokens = NativeWriteCodec.searchTokens("AC Inverter Hemat Energi", "Daikin")
        assertTrue(tokens.containsAll(listOf("inver", "hemat", "ener", "daik")))
        assertEquals(tokens.distinct(), tokens)
        assertTrue(NativeWriteCodec.searchTokens("a".repeat(1000), "b".repeat(1000)).size <= 1000)
    }
    private val image = mapOf("storagePath" to "drive/11111111-1111-4111-8111-111111111111", "driveFileId" to "google-file-id-123", "provider" to "drive", "url" to "")
    private fun product(status: String = "active") = mapOf<String, Any?>(
        "id" to "product-id", "name" to "Daikin 1 PK", "brand" to "Daikin", "model" to "FTK", "category" to "split", "capacityPk" to "1",
        "condition" to "new", "price" to 4500000L, "description" to "AC", "specifications" to mapOf("Daya" to "800 W"),
        "images" to listOf(image), "mainImagePath" to image["storagePath"], "status" to status, "archived" to false, "version" to 1L,
        "createdAt" to "server-time", "updatedAt" to "server-time", "createdBy" to "private-uid", "updatedBy" to "private-uid", "lastRequestId" to "private-request", "device" to mapOf("deviceName" to "private-device"),
    )

    @Test fun equivalentRetryPayloadKeepsFingerprintDespiteMapOrderOrNumericRepresentation() {
        val first = mapOf("data" to mapOf("price" to 4500000L, "name" to "AC"), "images" to listOf("a", "b"))
        val same = mapOf("images" to listOf("a", "b"), "data" to mapOf("name" to "AC", "price" to 4500000.0))
        assertEquals(NativeWriteCodec.fingerprint(first), NativeWriteCodec.fingerprint(same))
        assertNotEquals(NativeWriteCodec.fingerprint(first), NativeWriteCodec.fingerprint(first + ("images" to listOf("b", "a"))))
    }

    @Test fun publicProjectionKeepsMultipleImageReferencesWithoutLeakingActorOrDevice() {
        val second = image + ("storagePath" to "drive/22222222-2222-4222-8222-222222222222")
        val projected = requireNotNull(NativeWriteCodec.publicProjection("product", "product-id", product() + ("images" to listOf(image, second))))
        assertEquals(listOf(image["storagePath"], second["storagePath"]), projected["mediaPaths"])
        assertEquals(2, (projected["images"] as List<*>).size)
        for (key in listOf("createdBy", "updatedBy", "lastRequestId", "device", "actorUid", "version")) assertFalse(key, projected.containsKey(key))
    }

    @Test fun draftInactiveAndArchivedProductsNeverProducePublicProjection() {
        assertNull(NativeWriteCodec.publicProjection("product", "id", product("draft")))
        assertNull(NativeWriteCodec.publicProjection("product", "id", product("inactive")))
        assertNull(NativeWriteCodec.publicProjection("product", "id", product() + ("archived" to true)))
    }

    @Test fun activitySnapshotIsCompleteButExcludesPrivateMetadata() {
        val snapshot = NativeWriteCodec.snapshot("product", product())
        assertEquals(setOf("id", "name", "brand", "model", "category", "capacityPk", "condition", "price", "description", "specifications", "images", "mainImagePath", "status", "archived", "version"), snapshot.keys)
        assertEquals(product()["images"], snapshot["images"])
        assertEquals("Daikin", snapshot["brand"])
        assertFalse(snapshot.containsKey("createdBy"))
        assertFalse(snapshot.containsKey("device"))
    }

    @Test fun inactivePartnerHidesLogoAndActivePartnerProjectionRetainsDisplayOrder() {
        val value = mapOf<String, Any?>("name" to "Mitra", "logo" to image, "altText" to "Logo", "website" to "", "displayOrder" to 2L, "active" to false, "archived" to false, "createdAt" to "t", "updatedAt" to "t")
        assertNull(NativeWriteCodec.publicProjection("partner", "id", value))
        val projected = requireNotNull(NativeWriteCodec.publicProjection("partner", "id", value + ("active" to true)))
        assertEquals(2L, projected["displayOrder"])
        assertEquals(listOf(image["storagePath"]), projected["mediaPaths"])
    }

    @Test fun portfolioPublishesSingleImageWithoutActorDataAndArchiveRemovesProjection() {
        for ((type, category) in listOf("portfolioResidential" to "residential", "portfolioCorporate" to "corporate")) {
            val value = mapOf<String, Any?>("id" to "photo-id", "category" to category, "image" to image,
                "archived" to false, "version" to 2L, "createdAt" to "t", "updatedAt" to "t",
                "createdBy" to "private-owner", "updatedBy" to "private-admin", "lastRequestId" to "private-request")
            val projected = requireNotNull(NativeWriteCodec.publicProjection(type, "photo-id", value))
            assertEquals(setOf("id", "category", "image", "archived", "createdAt", "updatedAt", "mediaPaths"), projected.keys)
            assertEquals(category, projected["category"])
            assertEquals(listOf(image["storagePath"]), projected["mediaPaths"])
            assertNull(NativeWriteCodec.publicProjection(type, "photo-id", value + mapOf("archived" to true, "image" to null)))
            assertEquals(setOf("id", "category", "image", "archived", "version"), NativeWriteCodec.snapshot(type, value).keys)
        }
    }

    @Test fun missingPortfolioImageAndUnknownTypesNeverProduceProjection() {
        assertNull(NativeWriteCodec.publicProjection("portfolioResidential", "id", mapOf("category" to "residential", "image" to null)))
        assertThrows(IllegalArgumentException::class.java) { NativeWriteCodec.publicProjection("users", "id", emptyMap()) }
    }
}
