package com.beamburst.casswatch.receivers

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.beamburst.casswatch.ui.alarms.PhoneFallbackReceiver
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
                EntryPointAccessors.fromApplication(
                    context.applicationContext, PhoneFallbackReceiver.AlarmEntryPoint::class.java,
                ).alarmSchedulingCoordinator().reconcileAll()
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
