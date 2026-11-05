package com.jayaguna.admin.features.portfolio.controller

import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.PortfolioImage

internal data class RestoredPortfolioAction(val kind: String, val item: PortfolioImage, val requestId: String)
internal data class RestoredPortfolioState(val category: String, val selected: List<PendingPortfolioImage>, val action: RestoredPortfolioAction?)

/** Small Android saved-state payload: editable input and stable request IDs, never tokens. */
internal object PortfolioUiStateCodec {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val categories = setOf("residential", "corporate")
    fun encode(state: RestoredPortfolioState): List<String> = buildList {
        add("1"); add(state.category); add(state.selected.size.toString())
        state.selected.forEach { add(it.id); add(it.uri); add(it.requestId) }
        val action = state.action
        add(action?.kind.orEmpty())
        if (action != null) {
            add(action.requestId); add(action.item.id); add(action.item.category); add(action.item.version.toString())
            val image = action.item.image
            add(if (image == null) "0" else "1")
            if (image != null) {
                add(image.storagePath); add(image.provider); add(image.driveFileId)
                add(image.fileName); add(image.mimeType); add(image.sizeBytes.toString())
            }
        }
    }

    fun decode(values: List<String>): RestoredPortfolioState? = runCatching {
        require(values.size in 4..42 && values.all { it.length <= 4096 })
        var index = 0
        fun next() = values[index++]
        require(next() == "1")
        val category = next().also { require(it in categories) }
        val count = next().toInt().also { require(it in 0..8) }
        val selected = List(count) {
            val id = next().also { require(uuid.matches(it)) }
            val uri = next().also { require(it.startsWith("content://")) }
            val request = next().also { require(uuid.matches(it)) }
            PendingPortfolioImage(id, uri, request)
        }
        require(selected.map { it.id }.distinct().size == count && selected.map { it.requestId }.distinct().size == count)
        val kind = next()
        val action = if (kind.isEmpty()) null else {
            require(count == 0 && kind in setOf("replace", "delete"))
            val request = next().also { require(uuid.matches(it)) }
            val id = next().also { require(uuid.matches(it)) }
            val actionCategory = next().also { require(it == category) }
            val version = next().toLong().also { require(it > 0) }
            val hasImage = next().also { require(it in setOf("0", "1")) } == "1"
            val image = if (!hasImage) null else {
                val path = next().also { require(it.startsWith("content://") || it.startsWith("drive/")) }
                Media(storagePath = path, provider = next(), driveFileId = next(), fileName = next(), mimeType = next(), sizeBytes = next().toLong().also { require(it >= 0) })
            }
            if (kind == "replace") require(image?.storagePath?.startsWith("content://") == true)
            RestoredPortfolioAction(kind, PortfolioImage(id, actionCategory, image, version = version), request)
        }
        require(index == values.size)
        RestoredPortfolioState(category, selected, action)
    }.getOrNull()
}
