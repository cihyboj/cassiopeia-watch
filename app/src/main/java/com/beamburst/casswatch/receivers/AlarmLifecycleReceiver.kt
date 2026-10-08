package com.beamburst.casswatch.receivers

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.beamburst.casswatch.ui.alarms.PhoneFallbackReceiver
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.AppScopes
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import timber.log.Timber

/** System broadcasts rebuild wall-clock occurrences and retry fallbacks after permission changes. */
class AlarmLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isAlarmLifecycleAction(intent.action)) return
        val pending = goAsync()
        AppScopes.io.launch {
            try {
                val coordinator = EntryPointAccessors.fromApplication(
                    context.applicationContext, PhoneFallbackReceiver.AlarmEntryPoint::class.java,
                ).alarmSchedulingCoordinator()
                if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    // A time broadcast is not evidence that the disconnected watch changed too.
                    LocalDataStorage.getDeviceAddresses(context).forEach { address ->
                        coordinator.update(address) { it.copy(watchPayloads = emptyMap()) }
                    }
                }
                coordinator.reconcileAll()
            } catch (error: Exception) {
                Timber.e(error, "Alarm lifecycle reconciliation failed for %s", intent.action)
            } finally {
                pending.finish()
            }
        }
    }
}

internal fun isAlarmLifecycleAction(action: String?): Boolean = action in setOf(
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
)
