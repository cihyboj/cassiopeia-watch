package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.HourlySignalSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class HourlySignalEvaluatorTest {

    private val allDays = DayOfWeek.entries.toSet()

    private fun dt(hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 5, 3, hour, minute)

    private fun settings(enabled: Boolean, start: Int, end: Int, days: Set<DayOfWeek> = allDays) =
        HourlySignalSettings(enabled = enabled, startHour = start, endHour = end, days = days)

    // ── disabled ─────────────────────────────────────────────────────────────
    @Test fun disabledAlwaysReturnsFalse() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(9), settings(false, 7, 18)))
    }

    // window 7-18 (chime hours 7..18)
    @Test fun window7_18_autoSync_00_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(0), settings(true, 7, 18)))
    }
    @Test fun window7_18_autoSync_06_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(6), settings(true, 7, 18)))
    }
    @Test fun window7_18_manual_0930_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(9, 30), settings(true, 7, 18)))
    }
    @Test fun window7_18_manual_1730_on() {
        // next boundary = 18, in [7,18] inclusive: ON (18:00 chime included)
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(17, 30), settings(true, 7, 18)))
    }
    @Test fun window7_18_autoSync_18_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(18), settings(true, 7, 18)))
    }
    @Test fun window7_18_manual_2330_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(23, 30), settings(true, 7, 18)))
    }

    // window 19-0 (chime hours 19..00)
    @Test fun window19_0_autoSync_18_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(18), settings(true, 19, 0)))
    }
    @Test fun window19_0_autoSync_12_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(12), settings(true, 19, 0)))
    }
    @Test fun window19_0_manual_2230_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(22, 30), settings(true, 19, 0)))
    }
    @Test fun window19_0_manual_2330_on() {
        // next boundary = 24, in [19,24] inclusive: ON (00:00 chime included)
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(23, 30), settings(true, 19, 0)))
    }

    // window 1-6 (chime hours 1..6)
    @Test fun window1_6_autoSync_00_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(0), settings(true, 1, 6)))
    }
    @Test fun window1_6_autoSync_06_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(6), settings(true, 1, 6)))
    }

    // window 13-0 (chime hours 13..00)
    @Test fun window13_0_autoSync_12_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(12), settings(true, 13, 0)))
    }
    @Test fun window13_0_autoSync_00_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(0), settings(true, 13, 0)))
    }

    // ── days of week ─────────────────────────────────────────────────────────
    @Test fun `enabled signal does not chime on excluded day`() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                             DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val saturday = LocalDateTime.of(2025, 6, 7, 10, 0) // Saturday
        assertFalse(HourlySignalEvaluator.shouldChimeAt(saturday, HourlySignalSettings(enabled = true, startHour = 7, endHour = 18, days = weekdays)))
    }

    @Test fun `enabled signal chimes on included weekday`() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                             DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val monday = LocalDateTime.of(2025, 6, 2, 10, 0) // Monday
        assertTrue(HourlySignalEvaluator.shouldChimeAt(monday, HourlySignalSettings(enabled = true, startHour = 7, endHour = 18, days = weekdays)))
    }

    @Test fun `empty days never chimes even if enabled`() {
        val s = HourlySignalSettings(enabled = true, startHour = 7, endHour = 18, days = emptySet())
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(10), s))
    }

    // ── window follows the sync hour ─────────────────────────────────────────

    @Test fun `sync at 01_55 with shift 1 turns 08-19 on at 07_55 and off at 19_55`() {
        val s = HourlySignalSettings(enabled = true, startHour = 7, endHour = 18, syncShift = 1)
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(1, 55), s))
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(7, 55), s))
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(13, 55), s))
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(19, 55), s))
    }

    @Test fun `a manual sync keeps the last chime of a shifted window`() {
        val s = HourlySignalSettings(enabled = true, startHour = 7, endHour = 18, syncShift = 1)
        // 19:00 is the last chime of 08-19; the unshifted rule would turn it off at 18:10.
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(18, 10), s))
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(19, 10), s))
    }

    @Test fun `a shifted night window wraps past midnight`() {
        val s = HourlySignalSettings(enabled = true, startHour = 19, endHour = 0, syncShift = 3)
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(21, 30), s))
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(2, 30), s))
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(3, 30), s))
    }
}
