package com.beamburst.casswatch.ui.alarms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.beamburst.casswatch.MainActivity
import com.beamburst.casswatch.R
import com.beamburst.casswatch.utils.AppScopes
import com.beamburst.casswatch.utils.canPostNotifications
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class PhoneFallbackReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_ALARM_INDEX = "alarm_index"
        const val EXTRA_EXPECTED_HASH = "expected_hash"
        const val EXTRA_FIRE_INSTANT = "fire_instant"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val ACTION_RECONCILE = "com.beamburst.casswatch.RECONCILE_ALARMS"
        const val ACTION_EXPIRE = "com.beamburst.casswatch.EXPIRE_ALARM"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AlarmEntryPoint {
        fun alarmSchedulingCoordinator(): AlarmSchedulingCoordinator
    }

    override fun onReceive(context: Context, intent: Intent) {
        val key = AlarmKey.from(intent.getStringExtra(EXTRA_DEVICE_ADDRESS), intent.getIntExtra(EXTRA_ALARM_INDEX, -1)) ?: return
        val reconcile = intent.action == ACTION_RECONCILE
        val expiry = intent.action == ACTION_EXPIRE || reconcile
        if (intent.data?.toString() != if (reconcile) "${key.uri}/reconcile" else if (expiry) "${key.uri}/expiry" else key.uri) return
        val hash = intent.getStringExtra(EXTRA_EXPECTED_HASH)
        if (!expiry && hash == null) return
        val receivedAt = SystemClock.elapsedRealtime()
        val pending = goAsync()
        AppScopes.io.launch {
            var completed = false
            var handoffReturned = false
            try {
                val remaining = alarmReceiverTimeRemaining(receivedAt, SystemClock.elapsedRealtime()) ?: return@launch
                withTimeout(remaining) {
                    val coordinator = EntryPointAccessors.fromApplication(context.applicationContext, AlarmEntryPoint::class.java)
                        .alarmSchedulingCoordinator()
                    if (expiry) coordinator.reconcile(key.normalizedAddress)
                    else {
                        val requested = coordinator.fallback(key, intent.getLongExtra(EXTRA_FIRE_INSTANT, -1), hash!!) { alarm ->
                            val effectiveTime = alarmTimeAtOccurrence(intent.getLongExtra(EXTRA_FIRE_INSTANT, -1), java.time.ZoneId.systemDefault())
                            val request = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                                putExtra(AlarmClock.EXTRA_HOUR, effectiveTime.hour)
                                putExtra(AlarmClock.EXTRA_MINUTES, effectiveTime.minute)
                                putExtra(AlarmClock.EXTRA_MESSAGE, alarm.name)
                                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            handoffBeforeDeadline(intent.getLongExtra(EXTRA_FIRE_INSTANT, -1),
                                System::currentTimeMillis,
                                { alarmReceiverTimeRemaining(receivedAt, SystemClock.elapsedRealtime()) != null },
                                {
                                    if (request.resolveActivity(context.packageManager) == null) {
                                        throw android.content.ActivityNotFoundException("No clock app resolves ACTION_SET_ALARM")
                                    }
                                },
                                { context.startActivity(request); handoffReturned = true })

                        }
                        if (requested) postFallbackRecovery(context, key, failed = false)
                        else {
                            val alarm = coordinator.snapshot(key.normalizedAddress).alarms.getOrNull(key.slot)
                            if (alarm?.fallbackStatus == "pending" && activeFallbackStatus(alarm, System.currentTimeMillis()) != null) {
                                postFallbackRecovery(context, key, failed = false, uncertain = true)
                            }
                        }
                    }
                }
                completed = true
            } catch (error: Exception) {
                Timber.e(error, "Phone fallback callback failed for %s", key)
                if (!expiry) postFallbackRecovery(context, key, failed = !handoffReturned, uncertain = handoffReturned)
            } finally {
                if (!completed) {
                    runCatching { scheduleAlarmReconciliation(context, key.normalizedAddress) }
                        .onFailure { Timber.w(it, "Cannot schedule durable alarm recovery for %s", key) }
                }
                pending.finish()
            }
        }
    }
}

// goAsync() is finite even when the process is already alive; leave time for finish().
internal const val ALARM_RECEIVER_TIMEOUT_MS = 8_000L

internal fun alarmReceiverTimeRemaining(receivedAt: Long, now: Long): Long? =
    (ALARM_RECEIVER_TIMEOUT_MS - (now - receivedAt).coerceAtLeast(0)).takeIf { it > 0 }

internal fun openFallbackClock(context: Context) {
    val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (error: Exception) {
        Timber.w(error, "Cannot open clock for manual alarm recovery")
        com.beamburst.casswatch.ui.common.AppSnackbar(context.getString(R.string.no_clock_app_found))
    }
}

/** Notification taps open the clock for manual verification, without a date-less retry. */
internal fun postFallbackRecovery(context: Context, key: AlarmKey, failed: Boolean, uncertain: Boolean = false) {
    try {
        val notifications = NotificationManagerCompat.from(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        // Channel importance is immutable. Retire the old HIGH routine-notice channel.
        manager.deleteNotificationChannel("alarm_fallback_recovery")
        if (!canPostNotifications(context)) return
        val channel = if (failed) "alarm_fallback_failure" else "alarm_fallback_silent"
        val importance = fallbackNotificationImportance(failed)
        manager.createNotificationChannel(NotificationChannel(channel, context.getString(R.string.alarm_fallback_channel), importance).apply {
            if (!failed) { setSound(null, null); enableVibration(false) }
        })
        val message = context.getString(when {
            failed -> R.string.alarm_fallback_failed
            uncertain -> R.string.alarm_fallback_unconfirmed
            else -> R.string.alarm_fallback_requested
        })
        val clock = Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val recovery = if (clock.resolveActivity(context.packageManager) != null) clock else Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, key.uri.hashCode(), recovery,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(key.uri, key.slot, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alarm_black_24dp)
            .setContentTitle(context.getString(R.string.alarm_fallback_title))
            .setContentText(message)
            .setSilent(!failed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build())
    } catch (error: SecurityException) {
        Timber.w(error, "Notification permission revoked while posting fallback recovery for %s", key)
    } catch (error: Exception) {
        Timber.w(error, "Cannot post fallback recovery notification for %s", key)
    }
}

internal fun fallbackNotificationImportance(failed: Boolean): Int =
    if (failed) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW

/** Resolution can block; both deadlines are checked again at the activity boundary. */
internal fun handoffBeforeDeadline(
    instant: Long,
    now: () -> Long,
    withinReceiverBudget: () -> Boolean,
    resolve: () -> Unit,
    start: () -> Unit,
) {
    resolve()
    check(withinReceiverBudget() && fallbackWakeAt(instant, now()) != null) { "Phone alarm handoff deadline elapsed" }
    // A return from Android is only a request; background launch may still be blocked.
    start()
}
