package com.jayaguna.admin.data.drive

import com.jayaguna.admin.core.security.BackendException
import org.junit.Assert.*
import org.junit.Test

class NativeDrivePolicyTest {
    @Test fun identifiersCannotEscapeFileOrFolderPaths() {
        assertEquals("1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd", NativeDrivePolicy.fileId(NativeDrivePolicy.PRODUCTS_FOLDER))
        for (id in listOf("../files/id", "file/../../secret", "id?alt=media", "file#fragment", "")) {
            assertThrows(BackendException::class.java) { NativeDrivePolicy.fileId(id) }
        }
        assertThrows(BackendException::class.java) { NativeDrivePolicy.folder("users") }
        assertNotEquals(NativeDrivePolicy.folder("product"), NativeDrivePolicy.folder("partner"))
    }

    @Test fun portfolioCategoriesUseOnlyTheirExistingCompanyFolders() {
        assertEquals("1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx", NativeDrivePolicy.folder("portfolioResidential"))
        assertEquals("10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK", NativeDrivePolicy.folder("portfolioCorporate"))
        assertEquals(4, NativeDrivePolicy.ENTITY_TYPES.map(NativeDrivePolicy::folder).toSet().size)
        assertEquals("portfolioResidential", com.jayaguna.admin.data.model.PortfolioImage(category = "residential").entityType)
        assertEquals("portfolioCorporate", com.jayaguna.admin.data.model.PortfolioImage(category = "corporate").entityType)
        assertThrows(IllegalArgumentException::class.java) { com.jayaguna.admin.data.model.PortfolioImage(category = "other").entityType }
    }

    @Test fun formatChecksRejectMimeSpoofingAndHashesRemainDeterministic() {
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        assertTrue(NativeDrivePolicy.imageHeader(png, "image/png"))
        assertFalse(NativeDrivePolicy.imageHeader(png, "image/jpeg"))
        assertFalse(NativeDrivePolicy.imageHeader(png, "image/svg+xml"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", NativeDrivePolicy.sha256("abc".toByteArray()))
    }
}
