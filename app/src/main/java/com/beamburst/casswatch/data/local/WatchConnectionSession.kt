package com.beamburst.casswatch.data.local

import com.beamburst.casswatch.utils.Utils
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for the BLE lifecycle. Selection never changes this state. */
@Singleton
class WatchConnectionSession @Inject constructor() {
    sealed interface State
    data object Disconnected : State
    data class Connecting(val address: String, val generation: Long) : State
    data class Ready(val address: String, val generation: Long) : State

    private val lock = Any()
    private var generation = 0L
    private class Work {
        val job = SupervisorJob()
        val mutex = Mutex()
    }
    private var work: Work? = null
    private val initializedState = MutableStateFlow<Ready?>(null)
    val initialized: StateFlow<Ready?> = initializedState.asStateFlow()

    fun initializationCompleted(expected: Ready) = synchronized(lock) {
        if (mutableState.value == expected) initializedState.value = expected
    }

    fun isInitialized(expected: Ready): Boolean = isCurrent(expected) && initializedState.value == expected

    suspend fun awaitInitialized(expected: Ready) {
        combine(state, initialized) { current, initialized -> current != expected || initialized == expected }.first { it }
        if (!isInitialized(expected)) throw com.beamburst.casswatch.pairing.WatchConnectionChangedException()
    }
    private val endListeners = mutableListOf<(Ready) -> Unit>()

    fun onEnded(listener: (Ready) -> Unit) = synchronized(lock) {
        endListeners += listener
    }

    val attemptedAddress: String? get() = when (val current = state.value) {
        is Connecting -> current.address
        is Ready -> current.address
        Disconnected -> null
    }

    fun invalidateReconnect() = synchronized(lock) { generation++ }

    val lastGeneration: Long get() = synchronized(lock) { generation }

    /** A child of this generation, with caller cancellation forwarded by finally. */
    suspend fun <T> withWork(expected: Ready, block: suspend () -> T): T = coroutineScope {
        val owner = synchronized(lock) {
            work?.takeIf { mutableState.value == expected }
        } ?: throw com.beamburst.casswatch.pairing.WatchConnectionChangedException()
        val child = CoroutineScope(coroutineContext.minusKey(Job) + owner.job).async {
            owner.mutex.withLock { block() }
        }
        try {
            child.await()
        } finally {
            child.cancel()
        }
    }

    private fun endWork(current: State) {
        initializedState.value = null
        work?.job?.cancel()
        work = null
        if (current is Ready) endListeners.forEach { it(current) }
    }
    private val mutableState = MutableStateFlow<State>(Disconnected)
    val state: StateFlow<State> = mutableState.asStateFlow()
    val ready: Ready? get() = state.value as? Ready
    val address: String? get() = ready?.address

    /** Compare-and-set prevents erasing setup published during a transport status check. */
    fun begin(expected: State, address: String): State = synchronized(lock) {
        if (mutableState.value == expected) {
            mutableState.value = Connecting(Utils.sanitizeMacAddress(address), ++generation)
            endWork(expected)
        }
        mutableState.value
    }

    fun established(address: String): Ready? = synchronized(lock) {
        val normalized = Utils.sanitizeMacAddress(address).takeIf { it.isNotBlank() } ?: return null
        when (val current = mutableState.value) {
            is Connecting -> {
                if (current.address != normalized) return null
                Ready(normalized, current.generation)
            }
            is Ready -> return current.takeIf { it.address == normalized }
            Disconnected -> return null // a cancelled attempt must not resurrect a session
        }.also {
            work = Work()
            mutableState.value = it
        }
    }

    fun renewed(expected: Ready): Ready? = synchronized(lock) {
        if (mutableState.value != expected) return null
        mutableState.value = Disconnected
        endWork(expected)
        Ready(expected.address, ++generation).also {
            work = Work()
            mutableState.value = it
        }
    }

    fun matching(address: String?): Ready? = ready?.takeIf {
        address != null && it.address == Utils.sanitizeMacAddress(address)
    }

    fun isCurrent(session: Ready): Boolean = ready == session

    fun ended(expected: State): Boolean = synchronized(lock) {
        if (mutableState.value != expected) return false
        mutableState.value = Disconnected
        endWork(expected)
        true
    }

    suspend fun awaitReady(): Ready = state.filterIsInstance<Ready>().first()
}
