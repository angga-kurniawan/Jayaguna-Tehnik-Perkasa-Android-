package com.jayaguna.admin.features.dashboard.controller

import com.jayaguna.admin.data.model.ActivityFilter
import com.jayaguna.admin.data.repository.ProductFilter

internal data class AdminUiSnapshot(val product: ProductFilter, val activity: ActivityFilter, val pendingKeyHash: String = "", val pendingRequest: String = "")

/** A small saved-state payload, excluding identities, credentials and server data. */
internal object AdminUiStateCodec {
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun encode(product: ProductFilter, activity: ActivityFilter, pendingKeyHash: String = "", pendingRequest: String = ""): List<String> = listOf(
        "2", product.search, product.category, product.status,
        activity.startDate, activity.endDate, activity.actorUid, activity.entityType, activity.action, pendingKeyHash, pendingRequest
    )

    fun decode(values: List<String>): AdminUiSnapshot? {
        if (values.any { it.length > 1000 }) return null
        if (!(values.size == 9 && values[0] == "1" || values.size == 11 && values[0] == "2")) return null
        val hash = values.getOrElse(9) { "" }
        val request = values.getOrElse(10) { "" }
        if (!(hash.isEmpty() && request.isEmpty() || hashPattern.matches(hash) && uuidPattern.matches(request))) return null
        return AdminUiSnapshot(ProductFilter(values[1], values[2], values[3]), ActivityFilter(values[4], values[5], values[6], values[7], values[8]), hash, request)
    }
}
