package com.jayaguna.admin.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.jayaguna.admin.data.model.*
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun Map<*, *>.text(key: String) = this[key] as? String ?: ""
internal fun Map<*, *>.number(key: String) = this[key] as? Number ?: 0
internal fun Map<*, *>.media() = Media(text("storagePath"), text("url"), text("provider"), text("driveFileId"), text("fileName"), text("mimeType"), number("sizeBytes").toLong())
internal fun Map<*, *>.device() = DeviceInfo(text("installationId"), text("deviceName"), text("brand"), text("model"), text("androidVersion"), text("appVersion"))
internal fun DeviceInfo.payload() = mapOf("installationId" to installationId, "deviceName" to deviceName, "brand" to brand, "model" to model, "androidVersion" to androidVersion, "appVersion" to appVersion)
internal fun Media.payload(): Map<String, Any> = buildMap {
    put("storagePath",storagePath); put("url", "")
    if (provider.isNotBlank()) put("provider",provider)
    if (driveFileId.isNotBlank()) put("driveFileId",driveFileId)
    if (fileName.isNotBlank()) put("fileName",fileName)
    if (mimeType.isNotBlank()) put("mimeType",mimeType)
    if (sizeBytes>0) put("sizeBytes",sizeBytes)
}
internal fun Product.payload(): Map<String, Any?> = mapOf("name" to name.trim(), "brand" to brand.trim(), "model" to model.trim(), "category" to category, "capacityPk" to capacityPk, "condition" to condition, "price" to price, "description" to description, "specifications" to specifications, "images" to images.map { it.payload() }, "mainImagePath" to mainImagePath, "status" to status)
internal fun Partner.payload(): Map<String, Any?> = mapOf("name" to name.trim(), "logo" to logo?.payload(), "altText" to altText.trim(), "website" to website.trim(), "displayOrder" to displayOrder, "active" to active)
internal fun PortfolioImage.payload(): Map<String, Any?> = mapOf("category" to category, "image" to image?.payload())
internal fun DocumentSnapshot.product(): Product {
    val m = data ?: emptyMap<String, Any>()
    return Product(id, m.text("name"), m.text("brand"), m.text("model"), m.text("category"), m.text("capacityPk"), m.text("condition"), (m["price"] as? Number)?.toDouble(), m.text("description"), (m["specifications"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap(), (m["images"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.media() } ?: emptyList(), m.text("mainImagePath"), m.text("status"), m["archived"] == true, m.number("version").toLong())
}
internal fun DocumentSnapshot.partner(): Partner {
    val m = data ?: emptyMap<String, Any>()
    return Partner(id, m.text("name"), (m["logo"] as? Map<*, *>)?.media(), m.text("altText"), m.text("website"), m.number("displayOrder").toInt(), m["active"] == true, m["archived"] == true, m.number("version").toLong())
}
internal fun DocumentSnapshot.portfolioImage(): PortfolioImage {
    val m = data ?: emptyMap<String, Any>()
    return PortfolioImage(id, m.text("category"), (m["image"] as? Map<*, *>)?.media(), m["archived"] == true, m.number("version").toLong())
}
internal fun DocumentSnapshot.adminUser(): AdminUser {
    val m = data ?: emptyMap<String, Any>()
    val status = m.text("status").ifBlank { if (m["active"] == true) "approved" else "inactive" }
    return AdminUser(id, m.text("name"), m.text("email"), m.text("role").ifBlank { "ADMIN" },
        status == "approved" && (!m.containsKey("active") || m["active"] == true), status)
}
private fun readable(value: Any?): String = when (value) {
    null -> "—"
    is Map<*, *> -> JSONObject(value).toString(2)
    is List<*> -> value.joinToString("\n") { readable(it) }
    else -> value.toString()
}
internal fun DocumentSnapshot.audit(): AuditEntry = (data ?: emptyMap()).audit(id)
internal fun Map<*, *>.audit(id: String = text("id")): AuditEntry {
    val time = when (val v = this["timestamp"]) {
        is Number -> DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss").withZone(ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(v.toLong()))
        is Timestamp -> DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss").withZone(ZoneId.systemDefault()).format(v.toDate().toInstant())
        is Map<*, *> -> { val secs = (v["_seconds"] ?: v["seconds"]) as? Number; secs?.let { DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochSecond(it.toLong())) } ?: "" }
        else -> v?.toString() ?: ""
    }
    val before = this["before"] as? Map<*, *>
    val after = this["after"] as? Map<*, *>
    val changes = if (before != null && after != null) {
        (before.keys + after.keys).filter { before[it] != after[it] }.associate { key ->
            key.toString() to Change(readable(before[key]), readable(after[key]))
        }
    } else (this["changedFields"] as? Map<*, *>)?.entries?.associate { (key, value) ->
        val change = value as? Map<*, *> ?: emptyMap<Any, Any>()
        key.toString() to Change(readable(change["before"]), readable(change["after"]))
    } ?: emptyMap()
    return AuditEntry(id, text("actorUid"), text("actorName"), text("actorRole"), text("action"), text("entityType"), text("entityId"), changes, time, text("requestId"), (this["device"] as? Map<*, *>)?.device() ?: DeviceInfo())
}
