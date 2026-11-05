package com.jayaguna.admin.features.auth.controller

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.jayaguna.admin.R
import com.jayaguna.admin.core.security.BackendException

/** Google authenticates the identity; the resulting token is exchanged with Firebase only. */
class GoogleSignInCoordinator(private val context: Context) {
    private val credentials = CredentialManager.create(context.applicationContext)

    suspend fun signIn(): String {
        val clientId = webClientId()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = try {
            credentials.getCredential(context = context, request = request).credential
        } catch (_: GetCredentialCancellationException) {
            throw BackendException("cancelled", "Pemilihan akun Google dibatalkan.")
        } catch (_: NoCredentialException) {
            throw BackendException("google-sign-in", "Tambahkan akun Google pada perangkat, lalu coba masuk kembali.")
        } catch (_: GetCredentialException) {
            throw BackendException("google-sign-in", "Google belum dapat menyelesaikan proses masuk. Periksa koneksi, Google Play services, dan konfigurasi masuk Google aplikasi.")
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw BackendException("google-sign-in", "Google belum memberikan identitas akun yang sesuai. Coba lagi.")
        }
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken.takeIf { it.isNotBlank() }
                ?: throw BackendException("google-sign-in", "Identitas Google belum tersedia. Coba masuk kembali.")
        } catch (_: GoogleIdTokenParsingException) {
            throw BackendException("google-sign-in", "Identitas Google belum dapat dibaca. Coba masuk kembali.")
        }
    }

    suspend fun clearCredentialState() {
        credentials.clearCredentialState(ClearCredentialStateRequest())
    }

    private fun webClientId(): String {
        // A direct reference keeps the explicit client in optimized release APKs.
        val pattern = Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com")
        val configured = context.getString(R.string.google_web_client_id).trim()
        if (pattern.matches(configured)) return configured
        // This generated resource is absent when Firebase has not been configured yet.
        val resource = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        val generated = if (resource == 0) "" else context.getString(resource).trim()
        if (pattern.matches(generated)) return generated
        throw BackendException(
            "google-sign-in-configuration",
            "Masuk Google belum dikonfigurasi. Pengelola perlu mengaktifkan provider Google pada Firebase dan memasang konfigurasi OAuth aplikasi yang sesuai."
        )
    }
}
