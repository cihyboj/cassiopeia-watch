package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.HourlySignalSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class HourlySignalEvaluatorTest {

    private fun dt(hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 5, 3, hour, minute)

    private fun settings(enabled: Boolean, start: Int, end: Int) =
        HourlySignalSettings(enabled = enabled, startHour = start, endHour = end)

    // ── disabled ─────────────────────────────────────────────────────────────
    @Test fun disabledAlwaysReturnsFalse() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(9), settings(false, 7, 18)))
    }

    // ── window 7–18 (chime hours 7..17) ──────────────────────────────────────
    @Test fun window7_18_autoSync_00_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(0), settings(true, 7, 18)))
    }
    @Test fun window7_18_autoSync_06_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(6), settings(true, 7, 18)))
    }
    @Test fun window7_18_manual_0930_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(9, 30), settings(true, 7, 18)))
    }
    @Test fun window7_18_manual_1730_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(17, 30), settings(true, 7, 18)))
    }
    @Test fun window7_18_autoSync_18_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(18), settings(true, 7, 18)))
    }
    @Test fun window7_18_manual_2330_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(23, 30), settings(true, 7, 18)))
    }

    // ── window 19–0 (chime hours 19..23) ─────────────────────────────────────
    @Test fun window19_0_autoSync_18_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(18), settings(true, 19, 0)))
    }
    @Test fun window19_0_autoSync_12_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(12), settings(true, 19, 0)))
    }
    @Test fun window19_0_manual_2230_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(22, 30), settings(true, 19, 0)))
    }
    @Test fun window19_0_manual_2330_off() {
        // next boundary = 24, NOT in [19,24) → OFF
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(23, 30), settings(true, 19, 0)))
    }

    // ── window 1–6 (chime hours 1..5) ────────────────────────────────────────
    @Test fun window1_6_autoSync_00_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(0), settings(true, 1, 6)))
    }
    @Test fun window1_6_autoSync_06_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(6), settings(true, 1, 6)))
    }

    // ── window 13–0 (chime hours 13..23) ─────────────────────────────────────
    @Test fun window13_0_autoSync_12_on() {
        assertTrue(HourlySignalEvaluator.shouldChimeAt(dt(12), settings(true, 13, 0)))
    }
    @Test fun window13_0_autoSync_00_off() {
        assertFalse(HourlySignalEvaluator.shouldChimeAt(dt(0), settings(true, 13, 0)))
    }
}
