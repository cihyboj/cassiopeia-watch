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
    private val _connectionGenerations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val connectionGenerations: StateFlow<Map<String, Long>> = _connectionGenerations.asStateFlow()

    /**
     * Address → connection generation at which GShockAPI's per-connection BLE caches
     * (CachedIO inside the library) became warm. Subsequent `setTime` calls on the same
     * generation skip the slow DST/WorldCities read handshake, dropping write latency from
     * 2–4 s ("Mode 1") to 3–8 ms ("Mode 2"). Used to gate second-boundary-aligned writes.
     */
    private val _cacheWarmGenerations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val cacheWarmGenerations: StateFlow<Map<String, Long>> = _cacheWarmGenerations.asStateFlow()

    companion object {
        /** Threshold below which a setTime duration is treated as a Mode-2 cache hit. */
        const val CACHE_WARM_THRESHOLD_MS = 300L
    }

    init {
        ProgressEvents.runEventActions(
            "WatchConnectionTracker-${System.identityHashCode(this)}",
            arrayOf(
                EventAction("WatchInitializationCompleted") {
                    mark(selector.currentAddress(), ConnectionState.CONNECTED)
                },
                EventAction("ConnectionSetupComplete") {
                    val address = selector.currentAddress()
                    mark(address, ConnectionState.CONNECTED)
                    incrementGeneration(address)
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
        _connectionGenerations.update { generations ->
            generations.filterKeys { !it.equals(normalizedAddress, ignoreCase = true) }
        }
        _cacheWarmGenerations.update { warm ->
            warm.filterKeys { !it.equals(normalizedAddress, ignoreCase = true) }
        }
    }

    fun connectionGeneration(address: String): Long {
        val normalizedAddress = Utils.sanitizeMacAddress(address)
        if (normalizedAddress.isBlank()) return 0L
        return connectionGenerations.value[normalizedAddress] ?: 0L
    }

    fun isCacheWarm(address: String?): Boolean {
        if (address == null) return false
        val normalizedAddress = Utils.sanitizeMacAddress(address)
        if (normalizedAddress.isBlank()) return false
        val current = _connectionGenerations.value[normalizedAddress] ?: return false
        val warmed = _cacheWarmGenerations.value[normalizedAddress] ?: return false
        return warmed == current
    }

    /** Records a setTime duration; flips cacheWarm true if it indicates a Mode-2 cache hit. */
    fun markCacheWarmIfFast(address: String?, durationMs: Long) {
        if (durationMs >= CACHE_WARM_THRESHOLD_MS) return
        markCacheWarmed(address)
    }

    /** Forces the current generation to be considered warmed (e.g. after a successful warm-on-connect). */
    fun markCacheWarmed(address: String?) {
        if (address == null) return
        val normalizedAddress = Utils.sanitizeMacAddress(address)
        if (normalizedAddress.isBlank()) return
        val gen = _connectionGenerations.value[normalizedAddress] ?: return
        _cacheWarmGenerations.update { warm ->
            warm
                .filterKeys { !it.equals(normalizedAddress, ignoreCase = true) }
                .plus(normalizedAddress to gen)
        }
    }

    private fun incrementGeneration(address: String?) {
        if (address == null) return
        val normalizedAddress = Utils.sanitizeMacAddress(address)
        if (normalizedAddress.isBlank()) return
        _connectionGenerations.update { generations ->
            val next = (generations[normalizedAddress] ?: 0L) + 1L
            generations
                .filterKeys { !it.equals(normalizedAddress, ignoreCase = true) }
                .plus(normalizedAddress to next)
        }
    }
}
