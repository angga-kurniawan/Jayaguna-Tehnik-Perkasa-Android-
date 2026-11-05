package com.jayaguna.admin.features.dashboard.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Serializes pagination while allowing a refresh to supersede an older fetch. */
internal class LatestListRequestGate(private val scope: CoroutineScope) {
    private class Request {
        var generation = 0L
        var pending = false
        var job: Job? = null
    }

    private val requests = mutableMapOf<String, Request>()

    fun <T> launch(
        key: String,
        replace: Boolean,
        onStart: () -> Unit,
        fetch: suspend () -> T,
        onResult: (T) -> Unit,
        onError: (Exception) -> Unit,
        onFinish: () -> Unit
    ): Job? {
        val request = requests.getOrPut(key) { Request() }
        if (request.pending && !replace) return null
        // Advance before cancellation: an old completion must never clear the
        // new request's busy flag or commit a stale page/cursor/error.
        val generation = ++request.generation
        request.job?.cancel()
        request.pending = true
        onStart()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = fetch()
                currentCoroutineContext().ensureActive()
                if (request.generation == generation) onResult(result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                if (request.generation == generation) onError(error)
            }
        }
        request.job = job
        job.invokeOnCompletion {
            if (request.generation == generation) {
                request.pending = false
                request.job = null
                onFinish()
            }
        }
        // Assign the job and completion handler before it can run inline. The
        // handler also resets busy if the parent scope was already cancelled.
        job.start()
        return job
    }
}
