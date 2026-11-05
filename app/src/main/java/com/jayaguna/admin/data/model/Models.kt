package com.jayaguna.admin.data.model

data class Media(val storagePath: String = "", val url: String = "", val provider: String = "",
    val driveFileId: String = "", val fileName: String = "", val mimeType: String = "", val sizeBytes: Long = 0)
data class Product(
    val id: String = "", val name: String = "", val brand: String = "", val model: String = "",
    val category: String = "split", val capacityPk: String = "", val condition: String = "new",
    val price: Double? = null, val description: String = "", val specifications: Map<String, String> = emptyMap(),
    val images: List<Media> = emptyList(), val mainImagePath: String = "", val status: String = "draft",
    val archived: Boolean = false, val version: Long = 0
)
data class Partner(
    val id: String = "", val name: String = "", val logo: Media? = null, val altText: String = "",
    val website: String = "", val displayOrder: Int = 0, val active: Boolean = true,
    val archived: Boolean = false, val version: Long = 0
)

/** One portfolio photograph, grouped by the existing company Drive folders. */
data class PortfolioImage(
    val id: String = "", val category: String = "residential", val image: Media? = null,
    val archived: Boolean = false, val version: Long = 0
) {
    val entityType: String get() = when (category) {
        "residential" -> "portfolioResidential"
        "corporate" -> "portfolioCorporate"
        else -> throw IllegalArgumentException("Kategori portofolio tidak valid.")
    }
}
data class Account(val uid: String = "", val email: String = "", val name: String = "", val role: String = "")
data class AdminUser(val uid: String = "", val name: String = "", val email: String = "", val role: String = "ADMIN", val active: Boolean = true, val status: String = "approved")
data class DeviceInfo(val installationId: String = "", val deviceName: String = "", val brand: String = "", val model: String = "", val androidVersion: String = "", val appVersion: String = "")
data class Change(val before: String = "", val after: String = "")
data class AuditEntry(
    val id: String = "", val actorUid: String = "", val actorName: String = "", val actorRole: String = "",
    val action: String = "", val entityType: String = "", val entityId: String = "",
    val changedFields: Map<String, Change> = emptyMap(), val timestamp: String = "", val requestId: String = "",
    val device: DeviceInfo = DeviceInfo()
)
data class Dashboard(val activeProducts: Int = 0, val draftProducts: Int = 0, val activePartners: Int = 0, val recent: List<AuditEntry> = emptyList())
data class ActivityFilter(val startDate: String = "", val endDate: String = "", val actorUid: String = "", val entityType: String = "", val action: String = "")
