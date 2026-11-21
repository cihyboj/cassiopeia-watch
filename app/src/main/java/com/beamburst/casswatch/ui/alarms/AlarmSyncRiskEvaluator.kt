package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.AutoSyncSchedule
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

data class AlarmSyncRisk(
    val nextAlarmAt: LocalDateTime,
    val nextAutoSyncAt: LocalDateTime?,
)

object AlarmSyncRiskEvaluator {
    fun evaluate(
        key: AlarmKey,
        alarm: AlarmSyncStorage.StoredAlarm,
        alarmDays: Set<DayOfWeek>,
        payloads: Map<Int, WatchAlarmPayload>,
        clock: Clock,
        zone: ZoneId,
        autoSyncEnabled: Boolean,
        adjustmentTimeMinutes: Int,
        adjustmentTimeHour: Int = 0,
    ): AlarmSyncRisk? {
        val instant = nextAlarmOccurrence(alarm, alarmDays, clock, zone) ?: return null
        if (watchHasOccurrence(key, alarm, instant.toEpochMilli(), payloads, clock.millis(), zone)) return null
        val now = LocalDateTime.ofInstant(clock.instant(), zone)
        // A future auto-sync is a useful display hint, never evidence that the watch has this alarm.
        return AlarmSyncRisk(LocalDateTime.ofInstant(instant, zone),
            if (autoSyncEnabled) AutoSyncSchedule.next(now, adjustmentTimeHour, adjustmentTimeMinutes) else null)
    }
}
