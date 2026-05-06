package com.beamburst.casswatch.ui.alarms

import org.avmedia.gshockapi.Alarm
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

data class AlarmSyncRisk(
    val nextAlarmAt: LocalDateTime,
    val nextAutoSyncAt: LocalDateTime?,
)

object AlarmSyncRiskEvaluator {
    private val autoSyncHours = listOf(0, 6, 12, 18)

    fun evaluate(
        alarm: Alarm,
        alarmIndex: Int,
        alarmDays: Set<DayOfWeek>,
        viewMode: AlarmViewMode,
        firedAts: Map<Int, Long>,
        isDirty: Boolean,
        syncedHashes: List<String>,
        now: LocalDateTime,
        autoSyncEnabled: Boolean,
        adjustmentTimeMinutes: Int,
    ): AlarmSyncRisk? {
        if (!alarm.enabled || !isDirty) return null

        val expectedHash = alarmHash(alarm.hour, alarm.minute, emptySet(), enabled = true)
        if (expectedHash in syncedHashes) return null

        val nextAlarmAt = nextOccurrence(alarm, alarmIndex, alarmDays, viewMode, firedAts, now) ?: return null
        val nextAutoSyncAt = if (autoSyncEnabled) nextAutoSync(now, adjustmentTimeMinutes) else null

        return when {
            nextAutoSyncAt == null -> AlarmSyncRisk(nextAlarmAt, null)
            nextAutoSyncAt.isBefore(nextAlarmAt) -> null
            else -> AlarmSyncRisk(nextAlarmAt, nextAutoSyncAt)
        }
    }

    private fun nextOccurrence(
        alarm: Alarm,
        alarmIndex: Int,
        alarmDays: Set<DayOfWeek>,
        viewMode: AlarmViewMode,
        firedAts: Map<Int, Long>,
        now: LocalDateTime,
    ): LocalDateTime? {
        if (viewMode == AlarmViewMode.WEEKLY && alarmDays.isEmpty() && firedAts.containsKey(alarmIndex)) {
            return null
        }

        val time = LocalTime.of(alarm.hour, alarm.minute)
        val selectedDays = if (viewMode == AlarmViewMode.WEEKLY && alarmDays.isNotEmpty()) {
            alarmDays
        } else {
            DayOfWeek.entries.toSet()
        }

        return (0L..7L)
            .asSequence()
            .map { now.toLocalDate().plusDays(it).atTime(time) }
            .firstOrNull { it.dayOfWeek in selectedDays && it.isAfter(now) }
    }

    private fun nextAutoSync(now: LocalDateTime, adjustmentTimeMinutes: Int): LocalDateTime {
        val minute = adjustmentTimeMinutes.coerceIn(0, 59)
        val today = now.toLocalDate()

        autoSyncHours.forEach { hour ->
            val candidate = today.atTime(hour, minute)
            if (candidate.isAfter(now)) return candidate
        }

        return today.plusDays(1).atTime(autoSyncHours.first(), minute)
    }
}
