package com.beamburst.casswatch.pairing

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import timber.log.Timber

const val WATCH_RESPONSE_TIMEOUT_MS = 8_000L

class WatchResponseTimeoutException : IllegalStateException("Watch response timed out")

/** Bound complete API calls, including reads hidden inside time and scratchpad writes. */
suspend fun <T> awaitWatchResponse(
    timeoutMs: Long = WATCH_RESPONSE_TIMEOUT_MS,
    block: suspend () -> T,
): T = try {
    withTimeout(timeoutMs) { block() }
} catch (error: TimeoutCancellationException) {
    Timber.w(error, "Watch response timed out after %d ms", timeoutMs)
    throw WatchResponseTimeoutException()
}

/** Call before committing clean flags, history success, or success feedback. */
suspend fun confirmWatchWrite(checkSession: () -> Unit) {
    yield()
    checkSession()
}
