package com.jayaguna.admin.features.partners.controller

import com.jayaguna.admin.data.model.Media
import com.jayaguna.admin.data.model.Partner

/** Small editable input only: local content URIs and stable IDs; never credentials or image bytes. */
internal object PartnerBatchUiStateCodec {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun encode(selected: List<PendingPartnerDraft>): List<String> = buildList {
        add("1"); add(selected.size.toString())
        selected.forEach { draft ->
            val p = draft.partner
            add(p.id); add(draft.requestId); add(if (draft.locked) "1" else "0")
            add(p.name); add(p.altText); add(p.website); add(p.displayOrder.toString())
            add(if (p.active) "1" else "0"); add(if (p.archived) "1" else "0"); add(p.version.toString())
            val logo = p.logo ?: Media()
            add(logo.storagePath); add(logo.url); add(logo.provider); add(logo.driveFileId)
            add(logo.fileName); add(logo.mimeType); add(logo.sizeBytes.toString())
        }
    }

    fun decode(values: List<String>): List<PendingPartnerDraft>? = runCatching {
        require(values.size in 2..138 && values.all { it.length <= 4096 })
        var position = 0
        fun next() = values[position++]
        fun flag() = next().also { require(it in setOf("0", "1")) } == "1"
        require(next() == "1")
        val count = next().toInt().also { require(it in 0..PartnerBatchController.MAX_LOGOS) }
        val drafts = List(count) {
            val id = next().also { require(uuid.matches(it)) }
            val requestId = next().also { require(uuid.matches(it)) }
            val locked = flag()
            val name = next().also { require(it.length <= 160) }
            val altText = next().also { require(it.length <= 250) }
            val website = next().also { require(it.length <= 2048) }
            val order = next().toInt()
            val active = flag()
            val archived = flag().also { require(!it) }
            val version = next().toLong().also { require(it == 0L) }
            val path = next().also { require(validPartnerLogoUri(it)) }
            val url = next().also { require(it.isEmpty()) }
            val provider = next().also { require(it.isEmpty()) }
            val driveFileId = next().also { require(it.isEmpty()) }
            val fileName = next().also { require(it.isEmpty()) }
            val mime = next().also { require(it.isEmpty()) }
            val bytes = next().toLong().also { require(it == 0L) }
            PendingPartnerDraft(Partner(id = id, name = name, logo = Media(path, url, provider, driveFileId, fileName, mime, bytes),
                altText = altText, website = website, displayOrder = order, active = active, archived = archived, version = version), requestId, locked)
        }
        require(position == values.size)
        require(drafts.map { it.partner.id }.distinct().size == count)
        require(drafts.map { it.requestId }.distinct().size == count)
        require(drafts.map { it.partner.logo?.storagePath }.distinct().size == count)
        drafts
    }.getOrNull()
}
