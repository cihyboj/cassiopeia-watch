package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import org.avmedia.gshockapi.IGShockAPI
import com.beamburst.casswatch.utils.Utils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes all connection attempts. Without this, a CDM presence event and a manual
 * "Connect" tap (or two presence events for different watches) can run concurrent
 * `waitForConnection` calls against one BLE stack — a classic source of status-133
 * failures — and an attempt that never completes parks its coroutine forever.
 */
@Singleton
class WatchConnectCoordinator @Inject constructor(
    private val repository: IGShockAPI,
    private val session: WatchConnectionSession,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    private val connectMutex = Mutex()

    /**
     * Attempts to connect to [address], waiting for any in-flight attempt to finish first.
     *
     * An existing connection only counts as success when it is to the REQUESTED watch
     * (verified against the address the API reported at connect time) — otherwise a
     * presence-triggered connection to watch A completing while a request for watch B
     * waits on the mutex would be misreported as B being connected. On a mismatch:
     * with [takeover] the existing connection is dropped and the requested watch is
     * connected; without it the attempt reports failure and leaves the connection alone.
     *
     * @return true when connected to the requested watch, false on timeout or mismatch.
     */
    suspend fun connect(
        address: String,
        takeover: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean = connectMutex.withLock {
        val requested = Utils.sanitizeMacAddress(address)
        if (repository.isConnected()) {
            val current = session.address.orEmpty()
            if (requested.isNotBlank() && current.equals(requested, ignoreCase = true)) {
                Timber.i("Already connected to %s; skipping connect", requested)
                return@withLock true
            }
            if (!takeover) {
                Timber.i(
                    "Connected to %s, not the requested %s; leaving the existing connection",
                    current.ifBlank { "<unknown>" }, address,
                )
                return@withLock false
            }
            Timber.i(
                "Connected to %s but %s was requested; disconnecting first",
                current.ifBlank { "<unknown>" }, address,
            )
            runCatching { repository.disconnect() }
                .onFailure { Timber.w(it, "Disconnect before takeover failed") }
        }
        // No live connection from here on: drop any identity left by a Disconnect event that
        // was missed or not yet delivered, so the wait below sees this attempt's event only.
        session.disconnected()
        val result = withTimeoutOrNull(timeoutMs) {
            repository.waitForConnection(address)
            if (!repository.isConnected()) return@withTimeoutOrNull false
            // A timed-out attempt can complete late while a different address is being
            // requested. Only the connection event identifies the watch that actually
            // connected; never label it with this request's address.
            val current = session.awaitAddress()
            val matches = repository.isConnected() && current.equals(requested, ignoreCase = true)
            if (!matches) {
                Timber.w("Connection completed for %s while %s was requested", current, requested)
            }
            matches
        }
        if (result == null) {
            Timber.w("Connection attempt to %s timed out after %d ms", address, timeoutMs)
        }
        result == true
    }
}
