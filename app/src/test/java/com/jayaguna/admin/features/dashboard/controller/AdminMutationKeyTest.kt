package com.jayaguna.admin.features.dashboard.controller

import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Partner
import com.jayaguna.admin.data.model.Product
import org.junit.Assert.*
import org.junit.Test

class AdminMutationKeyTest {
    @Test fun restoredProductPayloadHasTheSameKeyButNewVersionOrImageDoesNot() {
        val original = Product(id = "product", name = " Daikin ", version = 3, images = listOf(Media(storagePath = "content://photo/1")), mainImagePath = "content://photo/1")
        assertEquals(AdminMutationKey.fingerprint(original), AdminMutationKey.fingerprint(original.copy(name = "Daikin")))
        assertNotEquals(AdminMutationKey.fingerprint(original), AdminMutationKey.fingerprint(original.copy(version = 4)))
        assertNotEquals(AdminMutationKey.fingerprint(original), AdminMutationKey.fingerprint(original.copy(images = listOf(Media(storagePath = "content://photo/2")))))
    }

    @Test fun partnerAndOperatorOperationsKeepDifferentActionsAndVersionsSeparate() {
        val partner = Partner(id = "partner", name = "Mitra", version = 1)
        assertEquals(AdminMutationKey.fingerprint(partner), AdminMutationKey.fingerprint(partner.copy()))
        assertNotEquals(AdminMutationKey.fingerprint(partner), AdminMutationKey.fingerprint(partner.copy(version = 2)))
        assertEquals(AdminMutationKey.fingerprint(listOf("archivePartner", "partner", 1L)), AdminMutationKey.fingerprint(listOf("archivePartner", "partner", 1)))
        assertNotEquals(AdminMutationKey.fingerprint(listOf("archivePartner", "partner", 1)), AdminMutationKey.fingerprint(listOf("archiveProduct", "partner", 1)))
    }
}
