package com.jayaguna.admin.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.jayaguna.admin.BuildConfig
import com.jayaguna.admin.core.security.BackendException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")

/** Existing Node API transport; Firebase login and Firestore reads are independent. */
class BackendApi(
    private val auth: FirebaseAuth,
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
) {
    val configured get() = baseUrl.isNotBlank()

    private fun endpoint(path: String, service: String): URL {
        if (!configured) throw BackendException("failed-precondition", "$service belum dikonfigurasi. Hubungi pengelola aplikasi.")
        require(path.startsWith("/api/") && !path.contains("..")) { "Path layanan tidak valid." }
        val origin = BackendEndpointPolicy.node(baseUrl, BuildConfig.DEBUG)
        return URL(origin.toString().trimEnd('/') + path)
    }

    private suspend fun request(path: String, method: String, body: ByteArray? = null, mime: String = "application/json", progress: ((Float) -> Unit)? = null, service: String = "Layanan gambar"): ByteArray {
        // Validate before obtaining credentials or opening a connection.
        val target = endpoint(path, service)
        val token = auth.currentUser?.getIdToken(false)?.await()?.token
            ?: throw BackendException("unauthenticated", "Silakan masuk kembali.")
        val expectsImage = method == "GET" && path.matches(Regex("/api/drive/assets/[0-9a-fA-F-]{36}"))
        return withContext(Dispatchers.IO) {
            val connection = target.openConnection() as HttpURLConnection
            try {
                currentCoroutineContext().ensureActive()
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 90_000
                connection.useCaches = false
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Accept", if (expectsImage) "image/png,image/jpeg,image/webp" else "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", mime)
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { stream ->
                        var offset = 0
                        while (offset < body.size) {
                            currentCoroutineContext().ensureActive()
                            val count = minOf(16_384, body.size - offset)
                            stream.write(body, offset, count)
                            offset += count
                            progress?.invoke(offset.toFloat() / body.size.coerceAtLeast(1))
                        }
                    }
                }
                val status = connection.responseCode
                val input = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = input?.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_IMAGE_BYTES) throw BackendException("unavailable", "Respons $service terlalu besar.")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: byteArrayOf()
                if (status !in 200..299) {
                    val error = runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("error") }.getOrNull()
                    throw BackendException(error?.optString("code")?.takeIf { it.isNotBlank() } ?: "unavailable",
                        error?.optString("message")?.takeIf { it.isNotBlank() } ?: "$service belum dapat diakses (HTTP $status).")
                }
                val responseType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                if ((expectsImage && responseType !in IMAGE_TYPES) || (!expectsImage && responseType != "application/json")) {
                    throw BackendException("unavailable", "$service belum siap memberikan respons aplikasi. Coba lagi.")
                }
                bytes
            } catch (error: BackendException) {
                throw error
            } catch (_: IOException) {
                val message = if (target.host in setOf("10.0.2.2", "localhost", "127.0.0.1"))
                    "$service masih memakai server pengembangan yang belum dapat dijangkau dari perangkat ini. Hubungi pengelola aplikasi."
                else "$service tidak dapat dihubungi. Periksa koneksi atau hubungi pengelola aplikasi, lalu coba lagi."
                throw BackendException("unavailable", message)
            } finally { connection.disconnect() }
        }
    }

    suspend fun notifyAccessRequest(): JSONObject = decodeJson(request("/api/access/request", "POST", "{}".toByteArray(), service = "Layanan permohonan akses"), "Layanan permohonan akses").optJSONObject("result")
        ?: throw BackendException("unavailable", "Pemberitahuan permohonan belum dikonfirmasi server.")

    suspend fun companyDriveJson(path: String, method: String = "GET"): JSONObject {
        require(path.startsWith("/api/drive/")) { "Path Drive perusahaan tidak valid." }
        return decodeJson(request(path, method, if (method in setOf("POST", "DELETE")) "{}".toByteArray() else null,
            service = "Layanan Drive perusahaan"), "Layanan Drive perusahaan")
    }

    suspend fun uploadCompanyAsset(assetId: String, bytes: ByteArray, mime: String, progress: (Float) -> Unit): JSONObject {
        requireAssetId(assetId)
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES && mime in IMAGE_TYPES) { "Gunakan PNG, JPEG, atau WebP maksimal 5 MiB." }
        return decodeJson(request("/api/drive/assets/$assetId", "PUT", bytes, mime, progress, "Layanan Drive perusahaan"), "Layanan Drive perusahaan")
    }

    suspend fun companyImage(assetId: String): ByteArray {
        requireAssetId(assetId)
        return request("/api/drive/assets/$assetId", "GET", service = "Layanan Drive perusahaan")
    }

    private fun requireAssetId(id: String) {
        require(id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "ID gambar tidak valid." }
    }

    private fun decodeJson(bytes: ByteArray, service: String = "Layanan gambar"): JSONObject = try { JSONObject(String(bytes, Charsets.UTF_8)) }
    catch (_: Exception) { throw BackendException("unavailable", "Respons $service tidak valid.") }

}

