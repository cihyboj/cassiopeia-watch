package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.utils.Utils
import java.util.IdentityHashMap

/** Event adapter shared by the production handler and JVM lifecycle tests. */
class WatchConnectionEvents(private val session: WatchConnectionSession) {
    // Payload tokens only fence stale callbacks; they never decide transport renewal.
    private val transports = IdentityHashMap<Any, WatchConnectionSession.Ready>()
    private data class TransportEnd(val epoch: Long, val ready: WatchConnectionSession.Ready)
    private var reconnect: TransportEnd? = null
    private var lifecycleEpoch = 0L
    var needsInitialization: Boolean = false
        private set

    @Synchronized
    fun setup(address: String, device: Any): WatchConnectionSession.Ready? {
        lifecycleEpoch++
        needsInitialization = false
        val normalized = Utils.sanitizeMacAddress(address)
        val current = session.ready
        val ready = when {
            // The pinned library emits setup once from onDeviceReady for each transport.
            // Its BluetoothDevice payload identifies an address, not a GATT lifecycle.
            current != null && current.address == normalized -> {
                needsInitialization = true
                session.renewed(current)
            }
            session.state.value == WatchConnectionSession.Disconnected -> {
                val down = reconnect ?: return null
                if (down.epoch >= lifecycleEpoch) return null
                val old = down.ready
                if (old.address != normalized || old.generation != session.lastGeneration) return null
                session.begin(WatchConnectionSession.Disconnected, normalized)
                needsInitialization = true
                session.established(normalized)
            }
            else -> session.established(normalized)
        } ?: return null
        reconnect = null
        transports.entries.removeAll { it.value.generation < ready.generation - 1L }
        transports[device] = ready
        return ready
    }

    fun failed(sideEffects: (String?) -> Unit) = sideEffects(session.attemptedAddress)

    @Synchronized
    fun disconnectToken(device: Any): WatchConnectionSession.Ready? = transports[device]

    /** A status observation is ordered with raw events and setup on the same lock. */
    @Synchronized
    fun observeTransport(connected: Boolean, expected: WatchConnectionSession.Ready? = session.ready) {
        if (!connected) expected?.let { disconnect(it, false) }
    }

    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun disconnect(
        token: WatchConnectionSession.Ready,
        transportConnected: Boolean,
        sideEffects: (WatchConnectionSession.Ready) -> Unit = {},
    ): Boolean {
        lifecycleEpoch++
        // Even if transport is already connected again, this down belongs before the
        // next observed setup. End old work now and retain its reconnect eligibility.
        if (!session.ended(token)) return false
        reconnect = TransportEnd(lifecycleEpoch, token)
        sideEffects(token)
        return true
    }
}
