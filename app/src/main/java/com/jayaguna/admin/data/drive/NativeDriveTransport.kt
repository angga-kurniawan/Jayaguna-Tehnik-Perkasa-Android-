package com.jayaguna.admin.data.drive

import com.jayaguna.admin.core.security.BackendException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

internal data class DriveHttpResponse(val status: Int, val bytes: ByteArray) {
    fun json(): JSONObject = try { JSONObject(String(bytes, Charsets.UTF_8)) }
    catch (_: Exception) { throw BackendException("unavailable", "Respons Google Drive tidak valid. Coba lagi.") }
}

/** Only Google's fixed HTTPS endpoint receives the short-lived, on-device Drive token. */
internal class NativeDriveTransport(
    private val accessToken: suspend () -> String,
    private val invalidateToken: suspend (String) -> Unit,
    private val currentSession: () -> DriveAuthorizationSession,
) {
    private class OperationSession(val ticket: DriveAuthorizationSession) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<OperationSession>
    }

    /** Pins a multi-request upload/read/delete to the same UID and login generation. */
    suspend fun <T> inSession(operation: suspend () -> T): T {
        val ticket = currentCoroutineContext()[OperationSession]?.ticket ?: currentSession()
        checkSession(ticket)
        return withContext(OperationSession(ticket)) { operation().also { checkSession(ticket) } }
    }

    suspend fun request(path: String, method: String = "GET", body: ByteArray? = null,
                        contentType: String? = null, image: Boolean = false,
                        allowConflict: Boolean = false, progress: (Float) -> Unit = {}): DriveHttpResponse {
        require(path.startsWith("/drive/v3/") || path.startsWith("/upload/drive/v3/"))
        require(method in setOf("GET", "POST", "PATCH"))
        val ticket = currentCoroutineContext()[OperationSession]?.ticket ?: currentSession()
        var renewed = false
        for (attempt in 0..3) {
            currentCoroutineContext().ensureActive()
            checkSession(ticket)
            val token = accessToken()
            checkSession(ticket)
            val response = try {
                withContext(Dispatchers.IO) { execute(path, method, body, contentType, image, token, ticket, progress) }
            } catch (error: CancellationException) { throw error }
            catch (error: BackendException) { throw error }
            catch (_: IOException) {
                checkSession(ticket)
                if (attempt < 3) { backoff(attempt); continue }
                throw BackendException("unavailable", "Koneksi Google Drive gagal. Periksa koneksi lalu coba lagi dengan permintaan yang sama.")
            }
            checkSession(ticket)
            if (response.status in 200..299 || allowConflict && response.status == 409) return response
            if (response.status == 401 && !renewed && attempt < 3) {
                renewed = true
                invalidateToken(token)
                continue
            }
            val reason = runCatching {
                response.json().optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
            }.getOrNull().orEmpty()
            if (attempt < 3 && (response.status == 429 || response.status >= 500 ||
                    reason in setOf("rateLimitExceeded", "userRateLimitExceeded", "backendError"))) {
                backoff(attempt); continue
            }
            when {
                response.status == 401 -> throw BackendException("drive-authorization-required", "Izin Google Drive perlu diperbarui. Izinkan akses Drive menggunakan akun yang dipakai untuk masuk.")
                response.status == 404 -> throw BackendException("not-found", "Berkas atau folder Google Drive tidak dapat diakses. Periksa akun dan pembagian foldernya.")
                reason == "storageQuotaExceeded" -> throw BackendException("resource-exhausted", "Penyimpanan Google Drive akun pengunggah penuh.")
                reason in setOf("accessNotConfigured", "serviceDisabled") -> throw BackendException("failed-precondition", "Google Drive API belum aktif pada project OAuth aplikasi.")
                response.status == 403 -> throw BackendException("permission-denied", "Akun Google belum memiliki izin yang diperlukan pada folder atau berkas Drive perusahaan.")
                else -> throw BackendException("unavailable", "Google Drive belum dapat menyelesaikan operasi. Coba lagi.")
            }
        }
        throw BackendException("unavailable", "Google Drive belum merespons. Coba lagi.")
    }

    private fun checkSession(ticket: DriveAuthorizationSession) {
        if (currentSession() != ticket) throw BackendException("unauthenticated", "Akun telah berubah. Ulangi operasi dengan akun yang sedang masuk.")
    }

    private suspend fun backoff(attempt: Int) = delay(200L * (1 shl attempt) + Random.nextLong(100))

    private suspend fun execute(path: String, method: String, body: ByteArray?, contentType: String?,
                                image: Boolean, token: String, ticket: DriveAuthorizationSession, progress: (Float) -> Unit): DriveHttpResponse {
        val connection = URL("https://www.googleapis.com$path").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", if (image) "application/octet-stream" else "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", contentType ?: "application/json; charset=UTF-8")
                connection.outputStream.use { output ->
                    var offset = 0
                    progress(0f)
                    while (offset < body.size) {
                        currentCoroutineContext().ensureActive()
                        checkSession(ticket)
                        val length = minOf(16 * 1024, body.size - offset)
                        output.write(body, offset, length)
                        offset += length
                        progress((offset.toFloat() / body.size * .9f).coerceIn(0f, .9f))
                    }
                }
            }
            val status = connection.responseCode
            val limit = if (image && status in 200..299) NativeDrivePolicy.MAX_IMAGE_BYTES else 1024 * 1024
            if (connection.contentLengthLong > limit) throw BackendException("resource-exhausted", "Respons Google Drive melebihi batas ukuran.")
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    checkSession(ticket)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) throw BackendException("resource-exhausted", "Respons Google Drive melebihi batas ukuran.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            return DriveHttpResponse(status, bytes)
        } finally { connection.disconnect() }
    }
}
