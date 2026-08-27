package com.beamburst.casswatch.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.beamburst.casswatch.pairing.DeviceAssociationManager
import com.beamburst.casswatch.utils.AppScopes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Re-arms watch detection after a reboot: syncs CDM associations and restarts the BLE
 * fallback scan, so reconnection works before the user first opens the app.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun deviceAssociationManager(): DeviceAssociationManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        runCatching {
            handleBootCompleted(context)
        }.onFailure { error ->
            Timber.e(error, "Failed to handle boot")
        }
    }

    private fun handleBootCompleted(context: Context) {
        val manager = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootEntryPoint::class.java,
        ).deviceAssociationManager()

        // syncAssociations() launches its own coroutine; goAsync keeps the process
        // alive long enough for it to register the CDM observers and fallback scan.
        val pendingResult = goAsync()
        AppScopes.io.launch {
            try {
                manager.syncAssociations()
                delay(5_000)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
