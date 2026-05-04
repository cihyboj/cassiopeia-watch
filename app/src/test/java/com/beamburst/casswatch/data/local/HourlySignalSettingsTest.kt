package com.beamburst.casswatch.data.local

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
}
