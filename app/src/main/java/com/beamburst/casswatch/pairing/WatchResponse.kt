package com.beamburst.casswatch.pairing

import android.content.Context
import com.beamburst.casswatch.R
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import timber.log.Timber

const val WATCH_RESPONSE_TIMEOUT_MS = 8_000L

// GShockAPI 46018e5: settings = SettingsIO + TimeAdjustmentIO; init = AppInfoIO
// + ButtonPressedIO; alarms = two queued GETs (0x2A/0x2B) behind one deferred;
// one event = EventsIO title + time. TimeIO awaits up to 3 DST states + 6 city
// DST values + 5 city names (city 0 is encoded locally), for both prime and set.
// Opaque calls get one per-response allowance for each possible response.
const val WATCH_SETTINGS_TIMEOUT_MS = 2 * WATCH_RESPONSE_TIMEOUT_MS
const val WATCH_INITIALIZATION_TIMEOUT_MS = 2 * WATCH_RESPONSE_TIMEOUT_MS
const val WATCH_ALARMS_TIMEOUT_MS = 2 * WATCH_RESPONSE_TIMEOUT_MS
const val WATCH_EVENT_TIMEOUT_MS = 2 * WATCH_RESPONSE_TIMEOUT_MS
const val WATCH_TIME_TIMEOUT_MS = 14 * WATCH_RESPONSE_TIMEOUT_MS

class WatchResponseTimeoutException : IllegalStateException("Watch response timed out")

/** Bound a response, or an opaque call with its explicitly derived operation budget. */
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

/** Timeout diagnostics stay in Timber/history; screen feedback uses existing translations. */
fun Context.watchOperationError(error: Throwable?): String =
    if (error is WatchResponseTimeoutException) getString(R.string.api_error)
    else error?.message ?: getString(R.string.api_error)
