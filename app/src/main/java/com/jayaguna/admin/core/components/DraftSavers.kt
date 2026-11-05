package com.jayaguna.admin.core.components

import androidx.compose.runtime.saveable.Saver
import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Partner
import com.jayaguna.admin.data.model.Product
import org.json.JSONArray
import org.json.JSONObject

// Android saved state contains only editable catalog data, never passwords or auth tokens.
val ProductStateSaver = Saver<Product, String>(save = { product ->
    JSONObject().apply {
        put("id", product.id); put("name", product.name); put("brand", product.brand); put("model", product.model)
        put("category", product.category); put("capacityPk", product.capacityPk); put("condition", product.condition)
        put("price", product.price ?: JSONObject.NULL); put("description", product.description)
        put("specifications", JSONObject(product.specifications)); put("images", JSONArray(product.images.map { it.toJson() }))
        put("mainImagePath", product.mainImagePath); put("status", product.status); put("archived", product.archived); put("version", product.version)
    }.toString()
}, restore = { raw ->
    val json = JSONObject(raw)
    val specifications = json.getJSONObject("specifications")
    val images = json.getJSONArray("images")
    Product(id = json.getString("id"), name = json.getString("name"), brand = json.getString("brand"), model = json.getString("model"),
        category = json.getString("category"), capacityPk = json.getString("capacityPk"), condition = json.getString("condition"),
        price = if (json.isNull("price")) null else json.getDouble("price"), description = json.getString("description"),
        specifications = specifications.keys().asSequence().associateWith { specifications.getString(it) },
        images = (0 until images.length()).map { images.getJSONObject(it).toMedia() }, mainImagePath = json.getString("mainImagePath"),
        status = json.getString("status"), archived = json.getBoolean("archived"), version = json.getLong("version"))
})

val PartnerStateSaver = Saver<Partner, String>(save = { partner ->
    JSONObject().apply {
        put("id", partner.id); put("name", partner.name); put("logo", partner.logo?.toJson() ?: JSONObject.NULL)
        put("altText", partner.altText); put("website", partner.website); put("displayOrder", partner.displayOrder)
        put("active", partner.active); put("archived", partner.archived); put("version", partner.version)
    }.toString()
}, restore = { raw ->
    val json = JSONObject(raw)
    Partner(id = json.getString("id"), name = json.getString("name"), logo = if (json.isNull("logo")) null else json.getJSONObject("logo").toMedia(),
        altText = json.getString("altText"), website = json.getString("website"), displayOrder = json.getInt("displayOrder"),
        active = json.getBoolean("active"), archived = json.getBoolean("archived"), version = json.getLong("version"))
})

private fun Media.toJson() = JSONObject().put("storagePath", storagePath).put("url", url).put("provider",provider)
    .put("driveFileId",driveFileId).put("fileName",fileName).put("mimeType",mimeType).put("sizeBytes",sizeBytes)
private fun JSONObject.toMedia() = Media(storagePath = getString("storagePath"), url = optString("url", ""),
    provider=optString("provider",""),driveFileId=optString("driveFileId",""),fileName=optString("fileName",""),mimeType=optString("mimeType",""),sizeBytes=optLong("sizeBytes",0))
