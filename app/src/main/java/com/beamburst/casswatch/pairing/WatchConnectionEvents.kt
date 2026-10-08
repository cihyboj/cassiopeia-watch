package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.utils.Utils
import java.util.IdentityHashMap

/** Event adapter shared by the production handler and JVM lifecycle tests. */
class WatchConnectionEvents(private val session: WatchConnectionSession) {
    private val devices = IdentityHashMap<Any, WatchConnectionSession.Ready>()
    private var reconnect: WatchConnectionSession.Ready? = null
    private var activeDevice: Any? = null
    var needsInitialization: Boolean = false
        private set

    @Synchronized
    fun setup(address: String, device: Any): WatchConnectionSession.Ready? {
        needsInitialization = false
        val normalized = Utils.sanitizeMacAddress(address)
        val current = session.ready
        val ready = when {
            current != null && current.address == normalized && activeDevice !== device -> {
                needsInitialization = true
                session.renewed(current)
            }
            session.state.value == WatchConnectionSession.Disconnected -> {
                val old = reconnect ?: return null
                if (old.address != normalized || old.generation != session.lastGeneration) return null
                session.begin(WatchConnectionSession.Disconnected, normalized)
                needsInitialization = true
                session.established(normalized)
            }
            else -> session.established(normalized)
        } ?: return null
        reconnect = null
        activeDevice = device
        devices.entries.removeAll { it.value.generation < ready.generation - 1L }
        devices[device] = ready
        return ready
    }

    fun failed(sideEffects: (String?) -> Unit) = sideEffects(session.attemptedAddress)

    @Synchronized
    fun disconnectToken(device: Any): WatchConnectionSession.Ready? = devices[device]

    @Synchronized
    fun disconnect(
        token: WatchConnectionSession.Ready,
        transportConnected: Boolean,
        sideEffects: (WatchConnectionSession.Ready) -> Unit = {},
    ): Boolean {
        // A callback delivered after a replacement transport is already live cannot
        // identify its GATT in this API. A different setup device still renews above.
        if (transportConnected || !session.ended(token)) return false
        reconnect = token
        sideEffects(token)
        return true
    }
}
