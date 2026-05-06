package com.beamburst.casswatch.ui.alarms

import org.avmedia.gshockapi.Alarm
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

object AlarmSchedulePlanner {
    val AllDays: Set<DayOfWeek> = DayOfWeek.entries.toSet()

    fun applyWeeklySchedule(
        alarms: List<Alarm>,
        alarmDays: Map<Int, Set<DayOfWeek>>,
        now: LocalDateTime,
        viewMode: AlarmViewMode,
        firedAts: Map<Int, Long> = emptyMap()   // index → firedAt millis; empty = backward compat
    ): List<Alarm> {
        val today = now.dayOfWeek
        val currentTime = now.toLocalTime()

        return alarms.mapIndexed { index, alarm ->
            val selectedDays = alarmDays[index] ?: AllDays
            when {
                !alarm.enabled -> alarm                              // already off → pass through
                selectedDays.isEmpty() -> {
                    // Fire-once: no days selected
                    if (firedAts.containsKey(index)) {
                        alarm.copy(enabled = false)                  // consumed → disable on watch
                    } else {
                        alarm                                        // unfired → send enabled (watch fires daily)
                    }
                }
                else -> alarm.copy(
                    enabled = selectedDays.contains(alarm.nextPossibleRingDay(today, currentTime))
                )
            }
        }
    }

    fun mirrorChime(alarms: List<Alarm>, chimeEnabled: Boolean): List<Alarm> =
        if (alarms.isEmpty()) alarms
        else alarms.mapIndexed { index, alarm ->
            if (index == 0) alarm.copy(hasHourlyChime = chimeEnabled) else alarm
        }

    private fun Alarm.time(): LocalTime = LocalTime.of(hour, minute)

    private fun Alarm.nextPossibleRingDay(today: DayOfWeek, currentTime: LocalTime): DayOfWeek =
        if (time().isAfter(currentTime)) today else today.plus(1)
}
