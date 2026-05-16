package com.beamburst.casswatch.ui.alarms

import org.avmedia.gshockapi.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.util.Locale

class AlarmHubFormattersTest {
    @Test
    fun `alarm repeat label shows daily for missing days`() {
        assertEquals("Daily", alarmRepeatLabel(null, Locale.US))
    }

    @Test
    fun `alarm repeat label shows daily for all days`() {
        assertEquals("Daily", alarmRepeatLabel(DayOfWeek.entries.toSet(), Locale.US))
    }

    @Test
    fun `alarm repeat label shows one-time for empty days`() {
        assertEquals("One-time alarm", alarmRepeatLabel(emptySet(), Locale.US))
    }

    @Test
    fun `alarm repeat label shows selected weekday labels`() {
        assertEquals(
            "Mon Wed Fri",
            alarmRepeatLabel(
                setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                Locale.US
            )
        )
    }

    @Test
    fun `use weekday strip for partial selection when selector enabled`() {
        assertTrue(useAlarmWeekdayStrip(setOf(DayOfWeek.MONDAY), true))
        assertTrue(
            useAlarmWeekdayStrip(
                setOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY,
                    DayOfWeek.SATURDAY,
                ),
                true,
            )
        )
        assertFalse(useAlarmWeekdayStrip(DayOfWeek.entries.toSet(), true))
        assertFalse(useAlarmWeekdayStrip(emptySet(), true))
        assertFalse(useAlarmWeekdayStrip(setOf(DayOfWeek.MONDAY), false))
    }

    @Test
    fun `timer subtitle omits leading hour when below one hour`() {
        assertEquals("10:00", timerTabSubtitle(10 * 60))
    }

    @Test
    fun `timer subtitle includes hour when needed`() {
        assertEquals("1:05:09", timerTabSubtitle(3909))
    }

    @Test
    fun `signal subtitle shows enabled window`() {
        assertEquals(
            "07-18",
            signalTabSubtitle(HourlySignalUiState(enabled = true, startHour = 7, endHour = 18))
        )
    }

    @Test
    fun `signal subtitle shows off when disabled`() {
        assertEquals(
            "Off",
            signalTabSubtitle(HourlySignalUiState(enabled = false, startHour = 7, endHour = 18))
        )
    }

    @Test
    fun `alarm tab subtitle counts enabled alarms`() {
        val alarms = listOf(
            Alarm(7, 0, true, false, null),
            Alarm(8, 0, false, false, null),
            Alarm(9, 0, true, false, null),
        )

        assertEquals("2 ON", alarmTabSubtitle(alarms))
    }

    @Test fun `chimeCount standard window 7 to 18`() {
        assertEquals(11, chimeCount(7, 18))
    }

    @Test fun `chimeCount midnight end 19 to 0`() {
        assertEquals(5, chimeCount(19, 0))
    }

    @Test fun `chimeCount full day 1 to 0`() {
        assertEquals(23, chimeCount(1, 0))
    }
}
