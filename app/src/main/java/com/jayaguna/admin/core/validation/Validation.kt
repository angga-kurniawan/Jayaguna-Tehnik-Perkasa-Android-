package com.jayaguna.admin.core.validation

import com.jayaguna.admin.data.model.Partner
import com.jayaguna.admin.data.model.Product

object Validation {
    fun product(p: Product) {
        require(p.name.trim().length in 2..160) { "Nama produk harus 2–160 karakter." }
        require(p.brand.trim().length in 1..100) { "Merek wajib diisi, maksimal 100 karakter." }
        require(p.category in setOf("split", "cassette", "standing", "ducted", "central", "other")) { "Kategori tidak valid." }
        require(p.condition in setOf("new", "used")) { "Kondisi tidak valid." }
        require(p.status in setOf("draft", "active", "inactive")) { "Status tidak valid." }
        require(p.price == null || (p.price.isFinite() && p.price >= 0)) { "Harga harus berupa angka positif." }
        require(p.images.size <= 8) { "Maksimal 8 foto per produk." }
        require(p.images.isEmpty() || p.images.any { it.storagePath == p.mainImagePath }) { "Pilih gambar utama." }
        require(p.status != "active" || p.images.isNotEmpty()) { "Produk aktif memerlukan foto." }
    }
    fun partner(p: Partner) {
        require(p.name.trim().length in 2..160) { "Nama perusahaan harus 2–160 karakter." }
        require(!p.logo?.storagePath.isNullOrBlank()) { "Pilih logo perusahaan." }
        require(p.altText.isNotBlank()) { "Teks alternatif logo wajib diisi." }
        require(p.displayOrder >= 0) { "Urutan tampil tidak boleh negatif." }
        require(p.website.isBlank() || p.website.startsWith("https://")) { "Website harus menggunakan https://." }
    }
}
