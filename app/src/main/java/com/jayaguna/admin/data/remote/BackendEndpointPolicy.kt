package com.jayaguna.admin.data.remote

import com.jayaguna.admin.core.security.BackendException
import java.net.URI

/** URL validation is independent of the phone model or installation identity. */
internal object BackendEndpointPolicy {
    fun node(value: String, debug: Boolean): URI {
        val uri = parse(value)
        val host = uri.host?.removeSurrounding("[", "]")?.lowercase().orEmpty()
        val local = host in setOf("localhost", "10.0.2.2", "0.0.0.0", "::1", "::", "0:0:0:0:0:0:0:1") ||
            host.startsWith("127.") || host.endsWith(".localhost") || host.startsWith("::ffff:127.")
        if (local && !debug) {
            throw BackendException("failed-precondition", "Alamat lokal hanya tersedia pada build pengembangan.")
        }
        if (host.isBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath !in setOf("", "/") || (uri.port != -1 && uri.port !in 1..65535) ||
            (uri.scheme != "https" && !(uri.scheme == "http" && local && debug))) {
            throw BackendException("failed-precondition", "Alamat server aplikasi harus memakai HTTPS yang valid.")
        }
        return uri
    }

    private fun parse(value: String): URI = try { URI(value) }
    catch (_: Exception) { throw BackendException("failed-precondition", "Alamat server aplikasi tidak valid.") }
}
