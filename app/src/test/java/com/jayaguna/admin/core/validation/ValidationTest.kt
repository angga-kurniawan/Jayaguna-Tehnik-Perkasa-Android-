package com.jayaguna.admin.core.validation

import com.jayaguna.admin.data.model.*
import org.junit.Assert.assertThrows
import org.junit.Test

class ValidationTest {
    private val valid = Product(name = "AC inverter", brand = "Daikin")
    @Test fun draftCanBeSavedWithoutPhotos() { Validation.product(valid) }
    @Test fun publishingRequiresPhoto() { assertThrows(IllegalArgumentException::class.java) { Validation.product(valid.copy(status = "active")) } }
    @Test fun mainImageMustBelongToProduct() { assertThrows(IllegalArgumentException::class.java) { Validation.product(valid.copy(images = listOf(Media("media/one")), mainImagePath = "media/other")) } }
    @Test fun rejectsNonFinitePrice() { assertThrows(IllegalArgumentException::class.java) { Validation.product(valid.copy(price = Double.NaN)) } }
    @Test fun rejectsTooManyPhotos() { assertThrows(IllegalArgumentException::class.java) { Validation.product(valid.copy(images = (1..9).map { Media("$it") }, mainImagePath = "1")) } }
    @Test fun rejectsUnexpectedStatus() { assertThrows(IllegalArgumentException::class.java) { Validation.product(valid.copy(status = "published_by_client")) } }
    @Test fun rejectsUnsafePartnerWebsite() { assertThrows(IllegalArgumentException::class.java) { Validation.partner(Partner(name = "Mitra", logo = Media("media/logo"), altText = "Logo Mitra", website = "javascript:alert(1)")) } }
}
