// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.data.repository

import android.bluetooth.BluetoothDevice
import com.beamburst.casswatch.pairing.BleLinkStateReset
import org.avmedia.gshockapi.GShockAPI
import org.avmedia.gshockapi.IGShockAPI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GShockRepository @Inject constructor(
    private val api: GShockAPI
) : IGShockAPI by api {
    // Every app-initiated close goes through here; see BleLinkStateReset.
    override fun disconnect() {
        api.disconnect()
        BleLinkStateReset.afterClose()
    }

    override fun close() {
        api.close()
        BleLinkStateReset.afterClose()
    }

    override fun teardownConnection(device: BluetoothDevice) {
        api.teardownConnection(device)
        BleLinkStateReset.afterClose()
    }
}
