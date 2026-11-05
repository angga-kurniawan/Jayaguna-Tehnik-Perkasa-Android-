package com.jayaguna.admin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network/session reads; never signs out, changes accounts, or prints credentials. */
@RunWith(AndroidJUnit4::class)
class AuthConnectivityProbeDeviceTest {
    @Test fun reportsConnectivityAndSavedSessionWithoutChangingAccounts() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("loginConnectivityProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = FirebaseAuth.getInstance()
        report("hasSavedUser", (auth.currentUser != null).toString())
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = connectivity.getNetworkCapabilities(network)
        report("activeNetwork", (network != null).toString())
        report("internet", (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true).toString())
        report("validated", (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true).toString())
        for (host in listOf("securetoken.googleapis.com", "identitytoolkit.googleapis.com", "firestore.googleapis.com")) {
            withContext(Dispatchers.IO) {
                try {
                    report("$host.dnsCount", InetAddress.getAllByName(host).size.toString())
                } catch (error: Exception) {
                    failure("$host.dns", error)
                    return@withContext
                }
                var connection: HttpURLConnection? = null
                try {
                    connection = URL("https://$host/").openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.useCaches = false
                    report("$host.httpStatus", connection.responseCode.toString())
                } catch (error: Exception) { failure("$host.https", error) }
                finally { connection?.disconnect() }
            }
        }
        val user = auth.currentUser ?: run { report("refresh", "skipped-no-saved-user"); return@runBlocking }
        try {
            // Discard GetTokenResult without accessing its token or claims.
            withTimeout(45_000L) { user.getIdToken(true).await() }
            report("refresh", "success")
        } catch (error: Exception) { failure("refresh", error); return@runBlocking }
        try {
            val repository = (context.applicationContext as AdminApplication).repository
                ?: error("Repository unavailable")
            val account = withTimeout(30_000L) { repository.session() }
            report("session", "success")
            report("sessionRole", account.role.takeIf { it in setOf("OWNER", "ADMIN") } ?: "other")
        } catch (error: Exception) { failure("session", error) }
    }

    private fun report(name: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("loginProbe.$name", value.take(512))
        })
    }

    private fun failure(stage: String, error: Exception) {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        report("$stage.result", if (error is TimeoutCancellationException) "timeout" else "error")
        report("$stage.exception", error.javaClass.simpleName)
        when (error) {
            is FirebaseAuthException -> report("$stage.errorCode", error.errorCode)
            is FirebaseFirestoreException -> report("$stage.errorCode", error.code.name)
        }
        report("$stage.message", safeMessage(error.message.orEmpty()))
        var cause = error.cause
        repeat(3) { index ->
            val current = cause ?: return@repeat
            report("$stage.cause$index", "${current.javaClass.simpleName}: ${safeMessage(current.message.orEmpty())}")
            cause = current.cause?.takeUnless { it === current || it === error }
        }
    }

    private fun safeMessage(message: String): String = message
        .replace(Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE), "[url]")
        .replace(Regex("\\bBearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE), "Bearer [redacted]")
        .replace(Regex("\\b(?:access[_-]?token|id[_-]?token|refresh[_-]?token|server[_-]?auth[_-]?code|authorization[_-]?code|auth[_-]?code|token|code|assertion|credential|authorization)\\b[\\\"']?\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^,\\s;&}]+)", RegexOption.IGNORE_CASE), "[credential]")
        .replace(Regex("\\beyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[jwt]")
        .replace(Regex("\\bya29\\.[A-Za-z0-9._~-]+"), "[token]")
        .replace(Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE), "[email]")
        .replace(Regex("[A-Za-z0-9_./+~-]{36,}"), "[opaque-value]")
        .replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(512)
}
