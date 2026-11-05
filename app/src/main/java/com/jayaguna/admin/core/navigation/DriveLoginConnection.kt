package com.jayaguna.admin.core.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.jayaguna.admin.core.security.userMessage
import com.jayaguna.admin.core.components.LocalMediaRepository
import com.jayaguna.admin.data.drive.DriveAuthorizationManager
import com.jayaguna.admin.data.drive.DriveAuthorizationSession
import com.jayaguna.admin.data.model.Account
import com.jayaguna.admin.data.repository.FirebaseRepository
import com.jayaguna.admin.features.dashboard.controller.DriveState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class DriveLoginConnection(val state: DriveState, val retry: () -> Unit)

/** Starts consent as the approved Google session opens, and retains the pending ticket on rotation. */
@Composable
internal fun rememberDriveLoginConnection(
    account: Account?, authorization: DriveAuthorizationManager, repository: FirebaseRepository,
): DriveLoginConnection {
    val scope = rememberCoroutineScope()
    val media = LocalMediaRepository.current
    val currentAccount by rememberUpdatedState(account)
    var attemptedSession by rememberSaveable { mutableStateOf("") }
    var displayedSession by rememberSaveable { mutableStateOf("") }
    var pendingUid by rememberSaveable { mutableStateOf("") }
    var pendingEmail by rememberSaveable { mutableStateOf("") }
    var pendingGeneration by rememberSaveable { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var connected by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var checked by rememberSaveable { mutableStateOf(false) }
    var serial by remember { mutableLongStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun matches(ticket: DriveAuthorizationSession) = currentAccount?.uid == ticket.uid &&
        runCatching { authorization.currentSession() == ticket }.getOrDefault(false)

    fun sessionKey(ticket: DriveAuthorizationSession) = "${ticket.uid}:${ticket.email}:${ticket.generation}"

    suspend fun verify(ticket: DriveAuthorizationSession, attempt: Long) {
        val connection = repository.driveStatus()
        if (serial != attempt || !matches(ticket)) return
        connected = connection.connected
        email = connection.accountEmail
        checked = true
        error = null
        // Failed requests from the pre-consent screen must be retried with a fresh cache identity.
        media?.clear()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ticket = pendingUid.takeIf { it.isNotBlank() }?.let { DriveAuthorizationSession(it, pendingEmail, pendingGeneration) }
        pendingUid = ""; pendingEmail = ""; pendingGeneration = 0
        if (ticket != null && matches(ticket)) {
          val attempt = ++serial
          busy = true; checked = false
          job = scope.launch {
            try {
                authorization.complete(ticket, result.resultCode, result.data)
                verify(ticket, attempt)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (serial == attempt && matches(ticket)) { connected = false; checked = true; error = failure.userMessage() }
            } finally { if (serial == attempt) busy = false }
          }
        }
    }

    val retry: () -> Unit = {
        if (!busy && pendingUid.isBlank() && currentAccount != null) {
          val ticket = runCatching { authorization.currentSession() }.getOrElse { failure ->
              checked = true; error = failure.userMessage(); null
          }
          if (ticket != null && matches(ticket)) {
          val request = ++serial
          attemptedSession = sessionKey(ticket)
          busy = true; connected = false; error = null; checked = false
          job = scope.launch {
            try {
                val attempt = authorization.authorize()
                if (serial == request && matches(ticket) && attempt.session == ticket) {
                    val resolution = attempt.pendingIntent
                    if (resolution == null) verify(attempt.session, request)
                    else {
                        pendingUid = attempt.session.uid
                        pendingEmail = attempt.session.email
                        pendingGeneration = attempt.session.generation
                        launcher.launch(IntentSenderRequest.Builder(resolution.intentSender).build())
                    }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (serial == request && matches(ticket)) {
                    pendingUid = ""; pendingEmail = ""; pendingGeneration = 0
                    checked = true; error = failure.userMessage()
                }
            } finally { if (serial == request) busy = false }
          }
          }
        }
    }

    LaunchedEffect(account?.uid, authorization, pendingUid, busy, checked) {
        if (account == null) return@LaunchedEffect
        val session = runCatching { authorization.currentSession() }.getOrElse { failure ->
            checked = true; error = failure.userMessage(); return@LaunchedEffect
        }
        val key = sessionKey(session)
        if (displayedSession != key) {
            serial++
            job?.cancel()
            displayedSession = key
            busy = false
            connected = false; checked = false; error = null; email = ""
            // Keep an outstanding dialog's original ticket until its callback drains.
        }
        if (!busy && pendingUid.isBlank() && (attemptedSession != key || !checked)) retry()
    }
    return DriveLoginConnection(DriveState(busy || pendingUid.isNotBlank(), connected, email, error, checked), retry)
}
