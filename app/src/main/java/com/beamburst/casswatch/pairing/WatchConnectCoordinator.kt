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

    val connectionEvents = WatchConnectionEvents(session)

    private val connectMutex = Mutex()
    private data class Attempt(val token: WatchConnectionSession.Connecting, val result: Deferred<Boolean>)
    @Volatile private var attempt: Attempt? = null

    init {
        session.onEnded { ended ->
            attempt?.takeIf { it.token.generation == ended.generation }?.let {
                attempt = null
                it.result.cancel()
            }
        }
    }

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
                    val observed = session.ready
                    val connected = repository.isConnected()
                    connectionEvents.observeTransport(connected || (observed != null && repository.isConnected()), observed)
                    var state = session.state.value
                    if (state is WatchConnectionSession.Ready && (connected || repository.isConnected())) {
                        if (state.address == requested) return@withLock attempt.takeIf { !session.isInitialized(state) }
                        if (!takeover) return@withTimeoutOrNull false
                        stopCurrent()
                        state = session.state.value
                    } else if (state is WatchConnectionSession.Connecting) {
                        if (state.address == requested && attempt != null) return@withLock attempt
                        if (state.address == requested && connected) {
                            val job = SupervisorJob()
                            val result = CoroutineScope(currentCoroutineContext() + job).async(start = CoroutineStart.LAZY) {
                                val ready = session.awaitReady()
                                session.awaitInitialized(ready)
                                repository.isConnected() && ready.address == requested
                            }
                            result.invokeOnCompletion { job.cancel() }
                            return@withLock Attempt(state, result).also { attempt = it; owned = it }
                        }
                        if (state.address != requested) {
                            if (!takeover) return@withTimeoutOrNull false
                            stopCurrent()
                            state = session.state.value
                        }
                    }
                    var begun = session.begin(state, requested)
                    if (begun is WatchConnectionSession.Ready) {
                        // Setup may publish a new generation after the status check.
                        // Preserve that compare-and-set result instead of erasing it.
                        if (begun.address == requested && repository.isConnected()) return@withLock null
                        if (!takeover) return@withTimeoutOrNull false
                        stopCurrent()
                        begun = session.begin(session.state.value, requested)
                    }
                    val token = begun as? WatchConnectionSession.Connecting ?: return@withTimeoutOrNull false
                    val job = SupervisorJob()
                    val result = CoroutineScope(currentCoroutineContext() + job).async(start = CoroutineStart.LAZY) {
                        repository.waitForConnection(requested)
                        val ready = session.awaitReady()
                        if (ready.generation == token.generation) session.initializationCompleted(ready)
                        repository.isConnected() && ready.address == requested && ready.generation == token.generation
                    }
                    result.invokeOnCompletion { job.cancel() }
                    Attempt(token, result).also { attempt = it; owned = it }
                }
                if (active == null) {
                    val ready = session.ready ?: return@withTimeoutOrNull false
                    session.awaitInitialized(ready)
                    return@withTimeoutOrNull true
                }
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

    /** Untagged library completion events cannot identify the generation that initialized. */
    suspend fun initializeReconnect(expected: WatchConnectionSession.Ready) {
        var initialized = false
        try {
            withSession(expected, initializing = true) { check ->
                kotlin.check(repository.guardWrites(check).init()) { "Watch initialization failed" }
                session.initializationCompleted(expected)
                initialized = session.isInitialized(expected)
            }
        } finally {
            if (!initialized) withContext(NonCancellable) {
                connectMutex.withLock {
                    if (session.isCurrent(expected)) stopCurrent()
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
        initializing: Boolean = false,
        block: suspend (check: () -> Unit) -> T,
    ): T {
        if (!initializing) session.awaitInitialized(expected)
        return session.withWork(expected) {
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
    }

    suspend fun closeRejectedSetup(
        expected: WatchConnectionSession.State = session.state.value,
        generation: Long = session.lastGeneration,
    ) = connectMutex.withLock {
        if (session.state.value != expected || session.lastGeneration != generation) return@withLock
        Timber.w("Closing setup without an accepted connection session")
        stopCurrent()
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
