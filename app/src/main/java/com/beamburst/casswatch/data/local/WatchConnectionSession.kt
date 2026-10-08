package com.beamburst.casswatch.data.local

import com.beamburst.casswatch.utils.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
    private val mutableState = MutableStateFlow<State>(Disconnected)
    val state: StateFlow<State> = mutableState.asStateFlow()
    val ready: Ready? get() = state.value as? Ready
    val address: String? get() = ready?.address

    /** Compare-and-set prevents erasing setup published during a transport status check. */
    fun begin(expected: State, address: String): State = synchronized(lock) {
        if (mutableState.value == expected) {
            mutableState.value = Connecting(Utils.sanitizeMacAddress(address), ++generation)
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
        }.also { mutableState.value = it }
    }

    fun matching(address: String?): Ready? = ready?.takeIf {
        address != null && it.address == Utils.sanitizeMacAddress(address)
    }

    fun isCurrent(session: Ready): Boolean = ready == session

    fun ended(expected: State): Boolean = synchronized(lock) {
        if (mutableState.value != expected) return false
        mutableState.value = Disconnected
        true
    }

    suspend fun awaitReady(): Ready = state.filterIsInstance<Ready>().first()
}
