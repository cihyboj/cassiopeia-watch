package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.utils.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import org.avmedia.gshockapi.IGShockAPI
import javax.inject.Inject
import javax.inject.Singleton

/** Connection control never waits for session I/O or library response deferreds. */
@Singleton
class WatchConnectCoordinator @Inject constructor(
    private val repository: IGShockAPI,
    private val session: WatchConnectionSession,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    private val connectMutex = Mutex()
    private data class Attempt(val token: WatchConnectionSession.Connecting, val result: Deferred<Boolean>)
    private var attempt: Attempt? = null

    suspend fun connect(
        address: String,
        takeover: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        val requested = Utils.sanitizeMacAddress(address)
        if (requested.isBlank()) return false
        var owned: Attempt? = null
        var accepted = false
        try {
            // Includes mutex acquisition, BLE setup and library initialization.
            val result = withTimeoutOrNull(timeoutMs) {
                val active = connectMutex.withLock {
                    val state = session.state.value
                    if (state is WatchConnectionSession.Ready && repository.isConnected()) {
                        if (state.address == requested) return@withLock null
                        if (!takeover) return@withTimeoutOrNull false
                        stopCurrent()
                    } else if (state is WatchConnectionSession.Connecting) {
                        if (state.address == requested && attempt != null) return@withLock attempt
                        if (state.address != requested) {
                            if (!takeover) return@withTimeoutOrNull false
                            stopCurrent()
                        }
                    }
                    val token = session.begin(session.state.value, requested) as WatchConnectionSession.Connecting
                    val job = SupervisorJob()
                    val result = CoroutineScope(currentCoroutineContext() + job).async(start = CoroutineStart.LAZY) {
                        repository.waitForConnection(requested)
                        val ready = session.awaitReady()
                        repository.isConnected() && ready.address == requested && ready.generation == token.generation
                    }
                    result.invokeOnCompletion { job.cancel() }
                    Attempt(token, result).also { attempt = it; owned = it }
                }
                if (active == null) return@withTimeoutOrNull true
                active.result.await()
            } ?: false
            accepted = result
            return result
        } finally {
            // A cancelled caller must still release its own attempt, never a replacement.
            owned?.let { mine ->
                withContext(NonCancellable) {
                    connectMutex.withLock {
                        if (attempt === mine) {
                            if (!accepted) {
                                Timber.w("Connection attempt to %s ended before readiness", requested)
                                stopCurrent()
                            }
                            attempt = null
                        }
                    }
                }
            }
        }
    }

    suspend fun disconnect(address: String? = null) = connectMutex.withLock {
        if (address == null || session.attemptedAddress == Utils.sanitizeMacAddress(address)) stopCurrent()
    }

    // Called at event receipt: invalidation/cancellation must not wait for any coroutine.
    fun handleDisconnect(block: () -> Unit) = block()

    suspend fun <T> withSession(
        expected: WatchConnectionSession.Ready,
        selectedAddress: (() -> String?)? = null,
        block: suspend (check: () -> Unit) -> T,
    ): T = session.withWork(expected) {
        val check = {
            if (!repository.isConnected() || !session.isCurrent(expected) ||
                (selectedAddress != null && session.matching(selectedAddress()) != expected)) {
                throw WatchConnectionChangedException()
            }
        }
        check()
        try {
            block(check).also { confirmWatchWrite(check) }
        } catch (error: Exception) {
            if (error !is CancellationException) Timber.w(error, "Session work failed for %s", expected.address)
            throw error
        }
    }

    suspend fun closeRejectedSetup() = connectMutex.withLock {
        Timber.w("Closing setup without an accepted connection session")
        repository.disconnect()
    }

    private fun stopCurrent() {
        session.ended(session.state.value)
        session.invalidateReconnect()
        attempt?.result?.cancel()
        attempt = null
        // No handle exists for the library's detached startConnection job. Late setup
        // is explicitly closed by the event adapter/handler.
        repository.disconnect()
    }
}
