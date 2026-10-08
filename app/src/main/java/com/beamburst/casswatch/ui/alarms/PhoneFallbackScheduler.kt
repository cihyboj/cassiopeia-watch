package com.beamburst.casswatch.ui.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

internal enum class FallbackAvailability { SCHEDULED, UNAVAILABLE, ELAPSED }

/** Zero late-delivery tolerance: a clock hand-off after the occurrence can create tomorrow's alarm. */
internal fun fallbackWakeAt(fireInstant: Long, now: Long): Long? =
    if (fireInstant <= now) null else maxOf(now, fireInstant - 5 * 60 * 1000L)

internal fun fallbackIntent(context: Context, key: AlarmKey, expiry: Boolean = false) = Intent(context, PhoneFallbackReceiver::class.java).apply {
    data = Uri.parse(if (expiry) "${key.uri}/expiry" else key.uri)
    if (expiry) action = PhoneFallbackReceiver.ACTION_EXPIRE
    putExtra(PhoneFallbackReceiver.EXTRA_DEVICE_ADDRESS, key.normalizedAddress)
    putExtra(PhoneFallbackReceiver.EXTRA_ALARM_INDEX, key.slot)
}

@Singleton
internal class PhoneFallbackScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmTime: AlarmSchedulingTime,
) : AlarmScheduleBackend {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun uncertain(key: AlarmKey) = postFallbackRecovery(context, key, failed = false, uncertain = true)

    override fun recovery(address: String, instant: Long) = scheduleAlarmReconciliation(context, address, instant)

    override fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long): FallbackAvailability {
        val wakeAt = fallbackWakeAt(instant, alarmTime.clock.millis()) ?: return FallbackAvailability.ELAPSED
        return attemptExactFallback(::canScheduleExactAlarms) {
            val intent = fallbackIntent(context, key).apply {
                putExtra(PhoneFallbackReceiver.EXTRA_EXPECTED_HASH, alarmHash(alarm.hour, alarm.minute, emptySet(), true))
                putExtra(PhoneFallbackReceiver.EXTRA_FIRE_INSTANT, instant)
            }
            val pending = PendingIntent.getBroadcast(context, key.slot, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAt, pending)
        }
    }

    override fun expiry(key: AlarmKey, instant: Long?) {
        cancelIntent(key.slot, fallbackIntent(context, key, expiry = true))
        if (instant == null) return
        val pending = PendingIntent.getBroadcast(context, key.slot, fallbackIntent(context, key, expiry = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Expiry must work without exact-alarm permission. Payload construction also checks expiry.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant, pending)
    }

    override fun cancel(key: AlarmKey) {
        cancelFallback(key)
        expiry(key, null)
    }

    override fun cancelFallback(key: AlarmKey) = cancelIntent(key.slot, fallbackIntent(context, key))

    override fun cancelLegacy(slot: Int) = cancelIntent(slot, Intent(context, PhoneFallbackReceiver::class.java))

    private fun cancelIntent(slot: Int, intent: Intent) {
        PendingIntent.getBroadcast(context, slot, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }
}

internal fun attemptExactFallback(allowed: () -> Boolean, schedule: () -> Unit): FallbackAvailability = try {
    if (!allowed()) FallbackAvailability.UNAVAILABLE
    else {
        schedule()
        FallbackAvailability.SCHEDULED
    }
} catch (error: SecurityException) {
    Timber.w(error, "Exact alarm permission revoked while scheduling fallback")
    FallbackAvailability.UNAVAILABLE
}

/** Durable, non-exact recovery; this broadcast only reconciles and never hands off directly. */
internal fun scheduleAlarmReconciliation(context: Context, address: String, instant: Long = System.currentTimeMillis() + 5_000L) {
    val key = AlarmKey.from(address, 0) ?: return
    val intent = fallbackIntent(context, key).apply {
        action = PhoneFallbackReceiver.ACTION_RECONCILE
        data = Uri.parse("${key.uri}/reconcile")
    }
    val pending = PendingIntent.getBroadcast(context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant, pending)
}
