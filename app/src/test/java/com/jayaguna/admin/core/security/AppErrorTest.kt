package com.jayaguna.admin.core.security

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AppErrorTest {
    @Test fun audienceAndProviderDiagnosticsReturnOnlyFixedReasonWithoutProviderData() {
        val privatePayload = "fixture-user@example.test id_token=eyJfixture-secret-token"
        for (message in listOf("The audience is not allowed. $privatePayload", "Invalid AUD FIELD. $privatePayload")) {
            assertEquals(FirebaseAuthFailureReason.OAUTH_AUDIENCE, firebaseAuthFailureReason(message))
            assertEquals("oauth-audience", firebaseAuthFailureReason(message).diagnosticValue)
        }
        for (message in listOf("The given sign-in provider is disabled. $privatePayload", "PROVIDER HAS BEEN DISABLED: $privatePayload", "OPERATION_NOT_ALLOWED $privatePayload")) {
            assertEquals(FirebaseAuthFailureReason.PROVIDER_DISABLED, firebaseAuthFailureReason(message))
            assertEquals("provider-disabled", firebaseAuthFailureReason(message).diagnosticValue)
        }
    }

    @Test fun unknownProviderDetailsNeverBecomeDiagnosticValues() {
        for (message in listOf(null, "", "Auditor unavailable fixture-user@example.test eyJfixture-secret-token", "Invalid token without a known reason")) {
            assertEquals(FirebaseAuthFailureReason.UNKNOWN, firebaseAuthFailureReason(message))
            assertEquals("unknown", firebaseAuthFailureReason(message).diagnosticValue)
        }
    }

    @Test fun rejectedGoogleCredentialExplainsOAuthConfigurationWithoutClaimingOneCause() {
        for (code in listOf("ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_IDP_RESPONSE")) {
            val message = firebaseAuthUserMessage(code)
            assertTrue(message.contains("Firebase menolak identitas Google"))
            assertTrue(message.contains("Coba masuk kembali"))
            assertTrue(message.contains("project lain"))
            assertTrue(message.contains(code))
        }
    }

    @Test fun unknownFirebaseFailureExposesOnlyStableCodeWithoutBlamingAccount() {
        val message = firebaseAuthUserMessage("ERROR_INTERNAL_ERROR")
        assertTrue(message.contains("ERROR_INTERNAL_ERROR"))
        assertTrue(message.contains("pengelola"))
        assertFalse(message.contains("Periksa akun Google"))
    }

    @Test fun unsafeErrorCodesCannotExposeProviderPayloadOrInjectDiagnosticLines() {
        val privatePayload = "fixture-user@example.test eyJfixture-secret-token"
        for (code in listOf("ERROR_INTERNAL_ERROR\n$privatePayload", "ERROR_INTERNAL_ERROR: $privatePayload", privatePayload, "ERROR_" + "A".repeat(73))) {
            assertEquals("ERROR_UNCLASSIFIED", safeFirebaseAuthErrorCode(code))
            val message = firebaseAuthUserMessage(code)
            assertTrue(message.contains("ERROR_UNCLASSIFIED"))
            assertFalse(message.contains("fixture-user"))
            assertFalse(message.contains("fixture-secret-token"))
            assertFalse(message.contains('\n'))
        }
    }

    @Test fun knownFirebaseFailuresRetainTheirOwnRecoveryInstructions() {
        assertTrue(firebaseAuthUserMessage("ERROR_OPERATION_NOT_ALLOWED").contains("Konfigurasi layanan login"))
        assertTrue(firebaseAuthUserMessage("ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL").contains("menghubungkan akun Google"))
        assertTrue(firebaseAuthUserMessage("ERROR_USER_DISABLED").contains("Akun dinonaktifkan"))
        assertTrue(firebaseAuthUserMessage("ERROR_TOO_MANY_REQUESTS").contains("Terlalu banyak percobaan"))
    }

    @Test fun networkFailureStaysNetworkFailureWithoutLeakingRawTransportMessage() {
        val raw = "fixture-user@example.test Authorization: Bearer eyJfixture-secret-token"
        assertEquals("Koneksi internet diperlukan. Periksa koneksi lalu coba lagi.", IOException(raw).userMessage())
        val firebaseNetwork = firebaseAuthUserMessage("ERROR_NETWORK_REQUEST_FAILED")
        assertTrue(firebaseNetwork.contains("Periksa koneksi"))
        assertFalse(firebaseNetwork.contains("OAuth"))
    }
}
