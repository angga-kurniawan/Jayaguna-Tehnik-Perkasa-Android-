package com.jayaguna.admin.core.security

import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.FirebaseNetworkException
import java.io.IOException
class BackendException(val code: String, message: String) : IOException(message)

/** Only stable Firebase codes may reach UI or diagnostics; SDK messages can contain provider data. */
internal fun safeFirebaseAuthErrorCode(errorCode: String): String =
    errorCode.takeIf { it.matches(Regex("ERROR_[A-Z0-9_]{1,72}")) } ?: "ERROR_UNCLASSIFIED"

internal enum class FirebaseAuthFailureReason(val diagnosticValue: String) {
    OAUTH_AUDIENCE("oauth-audience"), PROVIDER_DISABLED("provider-disabled"), UNKNOWN("unknown")
}

/** Classify only recognizable SDK wording; never return any portion of its message. */
internal fun firebaseAuthFailureReason(message: String?): FirebaseAuthFailureReason {
    val text = message.orEmpty().take(8192)
    return when {
        Regex("\\b(audience|aud\\s+field)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
            FirebaseAuthFailureReason.OAUTH_AUDIENCE
        listOf("provider is disabled", "provider has been disabled", "provider is not enabled", "OPERATION_NOT_ALLOWED")
            .any { text.contains(it, ignoreCase = true) } -> FirebaseAuthFailureReason.PROVIDER_DISABLED
        else -> FirebaseAuthFailureReason.UNKNOWN
    }
}

internal fun firebaseAuthUserMessage(errorCode: String): String {
    val code = safeFirebaseAuthErrorCode(errorCode)
    val message = when (code) {
        "ERROR_TOO_MANY_REQUESTS" -> "Terlalu banyak percobaan. Silakan coba beberapa saat lagi."
        "ERROR_USER_DISABLED" -> "Akun dinonaktifkan. Hubungi OWNER untuk memeriksa akses."
        "ERROR_OPERATION_NOT_ALLOWED", "ERROR_APP_NOT_AUTHORIZED", "ERROR_INVALID_API_KEY" ->
            "Konfigurasi layanan login belum sesuai. Hubungi pengelola aplikasi."
        "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_IDP_RESPONSE" ->
            "Firebase menolak identitas Google. Coba masuk kembali dan periksa konfigurasi OAuth Google–Firebase. Client dari project lain perlu diizinkan pada provider Google Firebase."
        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
            "Email ini sudah terhubung dengan metode masuk lain. Hubungi pengelola untuk menghubungkan akun Google."
        "ERROR_NETWORK_REQUEST_FAILED" -> "Layanan login tidak dapat dihubungi. Periksa koneksi lalu coba lagi."
        else -> "Firebase belum dapat menyelesaikan login Google. Hubungi pengelola dengan kode berikut."
    }
    return "$message (kode: $code)"
}

fun Throwable.userMessage(): String = when (this) {
    is BackendException -> message ?: "Backend belum dapat diakses. Silakan coba lagi."
    is FirebaseAuthException -> firebaseAuthUserMessage(errorCode)
    is FirebaseNetworkException -> "Layanan login tidak dapat dihubungi. Periksa koneksi lalu coba lagi."
    is IOException -> "Koneksi internet diperlukan. Periksa koneksi lalu coba lagi."
    is IllegalArgumentException -> message ?: "Periksa data yang Anda isi."
    else -> "Operasi gagal. Periksa koneksi dan coba lagi."
}
