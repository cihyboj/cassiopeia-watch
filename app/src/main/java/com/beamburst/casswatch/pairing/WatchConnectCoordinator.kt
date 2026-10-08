package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.utils.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.gshockapi.IGShockAPI
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Owns connection attempts, takeovers and writes until their entire lifecycle completes. */
@Singleton
class WatchConnectCoordinator @Inject constructor(
    private val repository: IGShockAPI,
    private val session: WatchConnectionSession,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    private val connectMutex = Mutex()

    suspend fun connect(
        address: String,
        takeover: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean = connectMutex.withLock {
        val requested = Utils.sanitizeMacAddress(address)
        if (requested.isBlank()) return@withLock false
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                val observed = session.state.value
                // CONNECTED precedes onDeviceReady. Unknown identity is readiness pending,
                // not evidence of a different watch, and must never be cleared here.
                if (repository.isConnected()) {
                    val current = session.ready ?: session.awaitReady()
                    if (current.address == requested) return@withTimeoutOrNull true
                    if (!takeover) return@withTimeoutOrNull false
                    stopCurrent()
                }
                val current = session.begin(observed, requested)
                if (current is WatchConnectionSession.Ready) {
                    // Setup raced isConnected(): retain it and let the library initialize.
                    if (current.address != requested) {
                        if (!takeover) return@withTimeoutOrNull false
                        stopCurrent()
                        session.begin(session.state.value, requested)
                    }
                }
                if (session.state.value == WatchConnectionSession.Disconnected) {
                    session.begin(session.state.value, requested)
                }
                repository.waitForConnection(requested)
                val ready = session.awaitReady()
                repository.isConnected() && ready.address == requested
            }
            if (result == null) {
                Timber.w("Connection to %s timed out after %d ms", requested, timeoutMs)
                stopCurrent()
            }
            result == true
        } catch (cancelled: CancellationException) {
            stopCurrent()
            throw cancelled
        }
    }

    suspend fun disconnect(address: String? = null) = connectMutex.withLock {
        val current = session.state.value
        val currentAddress = when (current) {
            is WatchConnectionSession.Ready -> current.address
            is WatchConnectionSession.Connecting -> current.address
            else -> null
        }
        if (address == null || currentAddress == Utils.sanitizeMacAddress(address)) stopCurrent()
    }

    suspend fun handleDisconnect(block: () -> Unit) = connectMutex.withLock { block() }

    /** Hold ownership across suspensions; callers also validate selection before each write. */
    suspend fun <T> withSession(
        expected: WatchConnectionSession.Ready,
        selectedAddress: (() -> String?)? = null,
        block: suspend (check: () -> Unit) -> T,
    ): T = connectMutex.withLock {
        val check = {
            if (!repository.isConnected() || !session.isCurrent(expected) ||
                (selectedAddress != null && session.matching(selectedAddress()) != expected)) {
                throw WatchConnectionChangedException()
            }
        }
        check()
        block(check).also { check() }
    }

    private fun stopCurrent() {
        val ended = session.state.value
        session.ended(ended)
        // IGShockAPI.disconnect -> release cancels Nordic's queue and closes GATT even
        // while CONNECTING. The library's separately launched startConnection coroutine
        // has no cancellation handle; unexpected late setup is rejected by session.begin /
        // established rather than adopted as the next requested watch.
        repository.disconnect()
    }
}
