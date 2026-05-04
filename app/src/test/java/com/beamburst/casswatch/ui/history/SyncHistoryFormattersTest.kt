package com.beamburst.casswatch.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncHistoryFormattersTest {
    @Test
    fun `formats new enabled alarm state`() {
        assertEquals("06:05●", formatAlarmSyncState("06:05:true"))
    }

    @Test
    fun `formats new disabled alarm state`() {
        assertEquals("06:05○", formatAlarmSyncState("06:05:false"))
    }

    @Test
    fun `formats legacy day-mask alarm state`() {
        assertEquals("06:05●", formatAlarmSyncState("06:05:MON,FRI:true"))
    }

    @Test
    fun `formats hourly signal target`() {
        assertEquals("ON", formatHourlySignalTarget(true))
        assertEquals("OFF", formatHourlySignalTarget(false))
    }
}
