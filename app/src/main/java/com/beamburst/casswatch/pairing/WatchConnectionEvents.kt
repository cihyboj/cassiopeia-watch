package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import java.util.IdentityHashMap

/** Event adapter shared by the production handler and JVM lifecycle tests. */
class WatchConnectionEvents(private val session: WatchConnectionSession) {
    private val devices = IdentityHashMap<Any, WatchConnectionSession.Ready>()

    @Synchronized
    fun setup(address: String, device: Any): WatchConnectionSession.Ready? =
        session.established(address)?.also { devices[device] = it }

    @Synchronized
    fun disconnectToken(device: Any): WatchConnectionSession.Ready? = devices[device]

    fun disconnect(
        token: WatchConnectionSession.Ready,
        transportConnected: Boolean,
        sideEffects: (WatchConnectionSession.Ready) -> Unit,
    ): Boolean {
        // The pinned API has no generation in Disconnect. A callback from an older GATT
        // must never release a live manager, even when BluetoothDevice is reused for the
        // same address. Its observer updates transport state before Main dispatches events.
        if (transportConnected || !session.ended(token)) return false
        sideEffects(token)
        return true
    }
}
