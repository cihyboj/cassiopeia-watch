package com.beamburst.casswatch.receivers

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.beamburst.casswatch.ui.alarms.alarmStateAfterPhoneClockChange
import com.beamburst.casswatch.ui.alarms.PhoneFallbackReceiver
import android.app.PendingIntent
import android.net.Uri
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.AppScopes
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.beamburst.casswatch.ui.alarms.alarmReceiverTimeRemaining
import timber.log.Timber

/** System broadcasts rebuild wall-clock occurrences and retry fallbacks after permission changes. */
class AlarmLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isAlarmLifecycleAction(intent.action)) return
        val receivedAt = SystemClock.elapsedRealtime()
        val pending = goAsync()
        AppScopes.io.launch {
            var completed = false
            try {
                val remaining = alarmReceiverTimeRemaining(receivedAt, SystemClock.elapsedRealtime()) ?: return@launch
                withTimeout(remaining) {
                    reconcileLifecycle(context, intent.action)
                }
                completed = true
            } catch (error: Exception) {
                Timber.e(error, "Alarm lifecycle reconciliation failed for %s", intent.action)
            } finally {
                if (!completed) runCatching { scheduleLifecycleReconciliation(context, intent.action) }
                    .onFailure { Timber.w(it, "Cannot schedule durable alarm lifecycle recovery") }
                pending.finish()
            }
        }
    }

    private suspend fun reconcileLifecycle(context: Context, action: String?) {
        val coordinator = EntryPointAccessors.fromApplication(
            context.applicationContext, PhoneFallbackReceiver.AlarmEntryPoint::class.java,
        ).alarmSchedulingCoordinator()
        if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
            // A time broadcast is not evidence that the disconnected watch changed too.
            LocalDataStorage.getDeviceAddresses(context).forEach { address ->
                coordinator.update(address, ::alarmStateAfterPhoneClockChange)
            }
        }
        coordinator.reconcileAll()
    }

}

internal fun isAlarmLifecycleAction(action: String?): Boolean = action in setOf(
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
)

internal fun scheduleLifecycleReconciliation(context: Context, action: String?) {
    val intent = Intent(context, AlarmLifecycleReceiver::class.java).apply {
        this.action = action
        data = Uri.parse("casswatch://alarm-lifecycle/${action.orEmpty()}")
    }
    val pending = PendingIntent.getBroadcast(context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5_000L, pending)
}
