package com.beamburst.casswatch.data.local

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HourlySignalSettingsTest {
    @Test fun defaultValues() {
        val d = HourlySignalSettings.Default
        assertFalse(d.enabled)
        assertEquals(7, d.startHour)
        assertEquals(18, d.endHour)
    }

    @Test fun endHourZeroRepresents24() {
        val s = HourlySignalSettings(enabled = true, startHour = 19, endHour = 0)
        assertEquals(0, s.endHour)
    }

    @Test
    fun `key includes address suffix`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        assertEquals("HourlySignalEnabled_AA:BB:CC:DD:EE:FF", "HourlySignalEnabled_$addr")
        assertEquals("HourlySignalStartHour_AA:BB:CC:DD:EE:FF", "HourlySignalStartHour_$addr")
        assertEquals("HourlySignalEndHour_AA:BB:CC:DD:EE:FF", "HourlySignalEndHour_$addr")
        assertEquals("HourlySignalMigrated_AA:BB:CC:DD:EE:FF", "HourlySignalMigrated_$addr")
    }

    @Test fun `default days is all seven`() {
        val d = HourlySignalSettings.Default
        assertEquals(DayOfWeek.entries.toSet(), d.days)
    }

    @Test fun `days round-trip through serialization helpers`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val serialized = HourlySignalSettings.serializeDaysForTest(days)
        val parsed = HourlySignalSettings.parseDaysForTest(serialized)
        assertEquals(days, parsed)
    }

    @Test fun `empty days serializes and parses as empty set`() {
        val serialized = HourlySignalSettings.serializeDaysForTest(emptySet())
        val parsed = HourlySignalSettings.parseDaysForTest(serialized)
        assertEquals(emptySet<DayOfWeek>(), parsed)
    }
}
