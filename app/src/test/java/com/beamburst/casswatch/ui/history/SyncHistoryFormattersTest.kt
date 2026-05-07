package com.beamburst.casswatch.ui.history

import com.beamburst.casswatch.data.local.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncHistoryFormattersTest {
    @Test
    fun `maps connection tags to tones`() {
        assertEquals(SyncHistoryTone.SUCCESS, syncHistoryTone(SyncTrigger.CONNECTION_CONNECTED))
        assertEquals(SyncHistoryTone.WARNING, syncHistoryTone(SyncTrigger.AUTO_ON_RECONNECT))
        assertEquals(SyncHistoryTone.ERROR, syncHistoryTone(SyncTrigger.CONNECTION_DISCONNECTED))
        assertEquals(SyncHistoryTone.ERROR, syncHistoryTone(SyncTrigger.CONNECTION_FAILED))
    }

    @Test
    fun `maps manual and activity tags to tones`() {
        assertEquals(SyncHistoryTone.NEUTRAL, syncHistoryTone(SyncTrigger.MANUAL_SET_TIME))
        assertEquals(SyncHistoryTone.NEUTRAL, syncHistoryTone(SyncTrigger.MANUAL_SEND_SETTINGS))
        assertEquals(SyncHistoryTone.INFO, syncHistoryTone(SyncTrigger.AUTO))
        assertEquals(SyncHistoryTone.INFO, syncHistoryTone(SyncTrigger.WATCH_SHORT_PRESS))
        assertEquals(SyncHistoryTone.INFO, syncHistoryTone(SyncTrigger.WATCH_ALWAYS_CONNECTED))
    }

    @Test
    fun `failed entries override trigger tone`() {
        assertEquals(SyncHistoryTone.ERROR, syncHistoryTone(SyncTrigger.CONNECTION_CONNECTED, success = false))
        assertEquals(SyncHistoryTone.ERROR, syncHistoryTone(SyncTrigger.MANUAL_SET_TIME, success = false))
    }

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
