package com.beamburst.casswatch.ui.alarms

import org.avmedia.gshockapi.Alarm
import java.time.DayOfWeek
import java.time.Clock
import java.time.ZoneId
import java.time.LocalDateTime

object AlarmSchedulePlanner {
    val AllDays: Set<DayOfWeek> = DayOfWeek.entries.toSet()

    fun applyWeeklySchedule(
        alarms: List<Alarm>,
        alarmDays: Map<Int, Set<DayOfWeek>>,
        now: LocalDateTime,
        viewMode: AlarmViewMode,
        firedAts: Map<Int, Long> = emptyMap(),
        oneShotLocalDates: Map<Int, String> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.fixed(now.atZone(zone).toInstant(), zone),
    ): List<Alarm> {
        return alarms.mapIndexed { index, alarm ->
            val selectedDays = alarmDays[index] ?: AllDays
            when {
                !alarm.enabled -> alarm                              // already off → pass through
                selectedDays.isEmpty() -> {
                    // Fire-once: no days selected
                    val occurrence = oneShotLocalDates[index]?.let { AlarmOccurrence(java.time.LocalDate.parse(it), alarm.hour, alarm.minute) }
                    if (firedAts.containsKey(index) || occurrence == null || occurrence.isElapsed(clock, zone)) {
                        alarm.copy(enabled = false)                  // consumed → disable on watch
                    } else {
                        val instant = occurrence.resolveInstant(zone)
                        val effective = instant.atZone(zone)
                        val nextWatchRing = AlarmOccurrence.nextOccurrence(effective.hour, effective.minute, clock, zone).resolveInstant(zone)
                        alarm.copy(hour = effective.hour, minute = effective.minute, enabled = nextWatchRing == instant)
                    }
                }
                else -> {
                    val nextRing = AlarmOccurrence.nextOccurrence(alarm.hour, alarm.minute, clock, zone).resolveInstant(zone).atZone(zone)
                    alarm.copy(hour = nextRing.hour, minute = nextRing.minute, enabled = nextRing.dayOfWeek in selectedDays)
                }
            }
        }
    }

    fun mirrorChime(alarms: List<Alarm>, chimeEnabled: Boolean): List<Alarm> =
        if (alarms.isEmpty()) alarms
        else alarms.mapIndexed { index, alarm ->
            if (index == 0) alarm.copy(hasHourlyChime = chimeEnabled) else alarm
        }

}
