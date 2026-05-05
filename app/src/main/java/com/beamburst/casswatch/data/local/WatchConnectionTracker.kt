package com.beamburst.casswatch.data.local

import com.beamburst.casswatch.utils.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchConnectionTracker @Inject constructor(
    private val selector: CurrentWatchSelector,
) {
    enum class ConnectionState { CONNECTED, CONNECTING, DISCONNECTED, ERROR }

    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val states: StateFlow<Map<String, ConnectionState>> = _states.asStateFlow()

    init {
        ProgressEvents.runEventActions(
            "WatchConnectionTracker-${System.identityHashCode(this)}",
            arrayOf(
                EventAction("WatchInitializationCompleted") {
                    mark(selector.currentAddress(), ConnectionState.CONNECTED)
                },
                EventAction("ConnectionSetupComplete") {
                    mark(selector.currentAddress(), ConnectionState.CONNECTED)
                },
                EventAction("Disconnect") {
                    mark(selector.currentAddress(), ConnectionState.DISCONNECTED)
                },
                EventAction("DeviceDisappeared") {
                    mark(selector.currentAddress(), ConnectionState.DISCONNECTED)
                },
                EventAction("ConnectionFailed") {
                    mark(selector.currentAddress(), ConnectionState.ERROR)
                },
            )
        )
    }

    fun mark(address: String?, state: ConnectionState) {
        if (address == null) return
        val normalizedAddress = Utils.sanitizeMacAddress(address)
        if (normalizedAddress.isBlank()) return
        _states.update { states ->
            states
                .filterKeys { !it.equals(normalizedAddress, ignoreCase = true) }
                .plus(normalizedAddress to state)
        }
    }

    fun remove(address: String) {
        val normalizedAddress = Utils.sanitizeMacAddress(address)
        if (normalizedAddress.isBlank()) return
        _states.update { states ->
            states.filterKeys { !it.equals(normalizedAddress, ignoreCase = true) }
        }
    }
}
