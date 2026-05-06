package com.beamburst.casswatch.ui.alarms

import org.avmedia.gshockapi.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class AlarmSchedulePlannerTest {

    @Test
    fun weeklyModeDisablesAlarmsForOtherDays() {
        val alarms = listOf(
            Alarm(11, 45, true, false, "Monday"),
            Alarm(13, 45, true, false, "Monday")
        )

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = alarms,
            alarmDays = mapOf(
                0 to setOf(DayOfWeek.MONDAY),
                1 to setOf(DayOfWeek.MONDAY)
            ),
            now = LocalDateTime.of(2026, 5, 1, 16, 55),
            viewMode = AlarmViewMode.WEEKLY
        )

        assertEquals(listOf(false, false), planned.map { it.enabled })
    }

    @Test
    fun weeklyModeDisablesTodaysAlarmsAfterTheirTimePassed() {
        val alarms = listOf(
            Alarm(11, 15, true, false, "Friday"),
            Alarm(18, 0, true, false, "Friday")
        )

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = alarms,
            alarmDays = mapOf(
                0 to setOf(DayOfWeek.FRIDAY),
                1 to setOf(DayOfWeek.FRIDAY)
            ),
            now = LocalDateTime.of(2026, 5, 1, 16, 55),
            viewMode = AlarmViewMode.WEEKLY
        )

        assertEquals(listOf(false, true), planned.map { it.enabled })
    }

    @Test
    fun `weekly mode enables weekday alarm on Sunday after alarm time for Monday`() {
        val alarm = Alarm(11, 15, true, false, "Weekdays")

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to weekdays()),
            now = LocalDateTime.of(2026, 5, 3, 12, 0),
            viewMode = AlarmViewMode.WEEKLY
        )

        assertTrue(planned[0].enabled)
    }

    @Test
    fun `weekly mode enables weekday alarm on Monday before alarm time`() {
        val alarm = Alarm(11, 15, true, false, "Weekdays")

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to weekdays()),
            now = LocalDateTime.of(2026, 5, 4, 6, 0),
            viewMode = AlarmViewMode.WEEKLY
        )

        assertTrue(planned[0].enabled)
    }

    @Test
    fun `weekly mode keeps weekday alarm enabled on Tuesday after alarm time for Wednesday`() {
        val alarm = Alarm(11, 15, true, false, "Weekdays")

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to weekdays()),
            now = LocalDateTime.of(2026, 5, 5, 18, 0),
            viewMode = AlarmViewMode.WEEKLY
        )

        assertTrue(planned[0].enabled)
    }

    @Test
    fun `weekly mode disables weekday alarm on Friday after alarm time for Saturday`() {
        val alarm = Alarm(11, 15, true, false, "Weekdays")

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to weekdays()),
            now = LocalDateTime.of(2026, 5, 1, 12, 0),
            viewMode = AlarmViewMode.WEEKLY
        )

        assertFalse(planned[0].enabled)
    }

    @Test
    fun `weekly mode keeps weekday alarm disabled on Saturday after alarm time for Sunday`() {
        val alarm = Alarm(11, 15, true, false, "Weekdays")

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to weekdays()),
            now = LocalDateTime.of(2026, 5, 2, 12, 0),
            viewMode = AlarmViewMode.WEEKLY
        )

        assertFalse(planned[0].enabled)
    }

    @Test
    fun `weekly mode enables sparse alarm after off day for selected next day`() {
        val alarm = Alarm(11, 15, true, false, "Sparse")

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            now = LocalDateTime.of(2026, 5, 5, 18, 0),
            viewMode = AlarmViewMode.WEEKLY
        )

        assertTrue(planned[0].enabled)
    }

    @Test
    fun `weekly mode disables sparse alarm after selected day for off next day`() {
        val alarm = Alarm(11, 15, true, false, "Sparse")

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            now = LocalDateTime.of(2026, 5, 6, 18, 0),
            viewMode = AlarmViewMode.WEEKLY
        )

        assertFalse(planned[0].enabled)
    }

    @Test
    fun `weekly mode enables any selected day before alarm time`() {
        DayOfWeek.entries.forEach { day ->
            val alarm = Alarm(11, 15, true, false, "Any day")

            val planned = AlarmSchedulePlanner.applyWeeklySchedule(
                alarms = listOf(alarm),
                alarmDays = mapOf(0 to setOf(day)),
                now = dateFor(day).atTime(6, 0),
                viewMode = AlarmViewMode.WEEKLY
            )

            assertTrue("Expected alarm enabled before time on $day", planned[0].enabled)
        }
    }

    @Test
    fun `weekly mode enables after alarm time when tomorrow is selected`() {
        DayOfWeek.entries.forEach { day ->
            val alarm = Alarm(11, 15, true, false, "Tomorrow")
            val tomorrow = day.plus(1)

            val planned = AlarmSchedulePlanner.applyWeeklySchedule(
                alarms = listOf(alarm),
                alarmDays = mapOf(0 to setOf(tomorrow)),
                now = dateFor(day).atTime(18, 0),
                viewMode = AlarmViewMode.WEEKLY
            )

            assertTrue("Expected alarm enabled after $day alarm time for $tomorrow", planned[0].enabled)
        }
    }

    @Test
    fun `weekly mode disables after alarm time when tomorrow is not selected`() {
        DayOfWeek.entries.forEach { day ->
            val alarm = Alarm(11, 15, true, false, "Today only")

            val planned = AlarmSchedulePlanner.applyWeeklySchedule(
                alarms = listOf(alarm),
                alarmDays = mapOf(0 to setOf(day)),
                now = dateFor(day).atTime(18, 0),
                viewMode = AlarmViewMode.WEEKLY
            )

            assertFalse("Expected alarm disabled after $day alarm time when only $day is selected", planned[0].enabled)
        }
    }

    @Test
    fun `missing day selections behave as all days`() {
        val alarms = listOf(
            Alarm(11, 15, true, false, "Daily"),
            Alarm(9, 3, false, false, "Off")
        )

        val planned = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = alarms,
            alarmDays = emptyMap(),
            now = LocalDateTime.of(2026, 5, 1, 16, 55),
            viewMode = AlarmViewMode.SIMPLE
        )

        assertEquals(alarms, planned)
    }

    @Test
    fun `weekly mode fire-once unfired sends alarm enabled`() {
        // No days selected, alarm enabled, no firedAt → goes to watch enabled (all-days semantic)
        val alarm = Alarm(hour = 23, minute = 0, enabled = true, hasHourlyChime = false, name = null)
        val result = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to emptySet()),          // empty = fire-once
            now = LocalDateTime.of(2026, 5, 2, 10, 0),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = emptyMap()                         // not yet fired
        )
        assertTrue("fire-once unfired alarm must be sent enabled", result[0].enabled)
    }

    @Test
    fun `weekly mode fire-once consumed sends alarm disabled`() {
        val alarm = Alarm(hour = 9, minute = 0, enabled = true, hasHourlyChime = false, name = null)
        val result = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to emptySet()),
            now = LocalDateTime.of(2026, 5, 2, 10, 0),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = mapOf(0 to 1746172800000L)         // has been fired
        )
        assertFalse("fire-once consumed alarm must be sent disabled", result[0].enabled)
    }

    @Test
    fun `weekly mode fire-once explicitly disabled passes through disabled`() {
        val alarm = Alarm(hour = 9, minute = 0, enabled = false, hasHourlyChime = false, name = null)
        val result = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = listOf(alarm),
            alarmDays = mapOf(0 to emptySet()),
            now = LocalDateTime.of(2026, 5, 2, 10, 0),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = emptyMap()
        )
        assertFalse("disabled fire-once alarm must stay disabled", result[0].enabled)
    }

    @Test
    fun mirrorChimeUpdatesOnlyFirstAlarm() {
        val alarms = listOf(
            Alarm(hour = 9, minute = 0, enabled = true, hasHourlyChime = false, name = "First"),
            Alarm(hour = 10, minute = 0, enabled = true, hasHourlyChime = false, name = "Second")
        )

        val result = AlarmSchedulePlanner.mirrorChime(alarms, chimeEnabled = true)

        assertTrue(result[0].hasHourlyChime)
        assertFalse(result[1].hasHourlyChime)
    }

    private fun weekdays(): Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY
    )

    private fun dateFor(day: DayOfWeek): LocalDate =
        LocalDate.of(2026, 5, 4).plusDays((day.value - DayOfWeek.MONDAY.value).toLong())
}
