package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.repository.GShockRepository
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
    private val repository: GShockRepository,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    private val connectMutex = Mutex()

    /**
     * Attempts to connect to [address], waiting for any in-flight attempt to finish first.
     * @return true when connected, false when the attempt timed out.
     */
    suspend fun connect(address: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        connectMutex.withLock {
            if (repository.isConnected()) {
                Timber.i("Already connected; skipping connect to %s", address)
                return@withLock true
            }
            val result = withTimeoutOrNull(timeoutMs) {
                repository.waitForConnection(address)
                true
            }
            if (result == null) {
                Timber.w("Connection attempt to %s timed out after %d ms", address, timeoutMs)
            }
            result == true
        }
}
