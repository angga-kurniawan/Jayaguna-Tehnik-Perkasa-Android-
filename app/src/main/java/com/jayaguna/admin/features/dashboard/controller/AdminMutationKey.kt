package com.jayaguna.admin.features.dashboard.controller

import com.jayaguna.admin.data.model.Partner
import com.jayaguna.admin.data.model.Product
import com.jayaguna.admin.data.remote.payload
import com.jayaguna.admin.data.repository.NativeWriteCodec

/** Match the actual catalog payload, not an in-memory model object's identity. */
internal object AdminMutationKey {
    fun fingerprint(key: Any): String = NativeWriteCodec.fingerprint(when (key) {
        is Product -> mapOf("operation" to "saveProduct", "id" to key.id, "version" to key.version, "data" to key.payload())
        is Partner -> mapOf("operation" to "savePartner", "id" to key.id, "version" to key.version, "data" to key.payload())
        is List<*> -> key // Operator and archive mutations already use primitive operation keys.
        else -> throw IllegalArgumentException("Jenis perubahan tidak dikenali.")
    })
}
