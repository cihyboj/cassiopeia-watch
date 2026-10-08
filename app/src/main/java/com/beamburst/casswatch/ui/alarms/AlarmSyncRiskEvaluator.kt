package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.SyncEntry
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

data class AlarmSyncRisk(
    val nextAlarmAt: LocalDateTime,
    val nextAutoSyncAt: LocalDateTime?,
)

object AlarmSyncRiskEvaluator {
    private val autoSyncHours = listOf(0, 6, 12, 18)

    fun evaluate(
        key: AlarmKey,
        alarm: AlarmSyncStorage.StoredAlarm,
        alarmDays: Set<DayOfWeek>,
        history: List<SyncEntry>,
        clock: Clock,
        zone: ZoneId,
        autoSyncEnabled: Boolean,
        adjustmentTimeMinutes: Int,
    ): AlarmSyncRisk? {
        val instant = nextAlarmOccurrence(alarm, alarmDays, clock, zone) ?: return null
        if (watchHasOccurrence(key, alarm, instant.toEpochMilli(), history, clock.millis(), zone)) return null
        val now = LocalDateTime.ofInstant(clock.instant(), zone)
        // A future auto-sync is a useful display hint, never evidence that the watch has this alarm.
        return AlarmSyncRisk(LocalDateTime.ofInstant(instant, zone),
            if (autoSyncEnabled) nextAutoSync(now, adjustmentTimeMinutes) else null)
    }

    private fun nextAutoSync(now: LocalDateTime, adjustmentTimeMinutes: Int): LocalDateTime {
        val minute = adjustmentTimeMinutes.coerceIn(0, 59)
        autoSyncHours.forEach { hour ->
            val candidate = now.toLocalDate().atTime(hour, minute)
            if (candidate.isAfter(now)) return candidate
        }
        return now.toLocalDate().plusDays(1).atTime(autoSyncHours.first(), minute)
    }
}
