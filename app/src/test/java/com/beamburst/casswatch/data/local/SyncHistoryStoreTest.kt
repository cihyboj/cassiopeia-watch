package com.beamburst.casswatch.data.local

import org.junit.Assert.*
import org.junit.Test

class SyncHistoryStoreTest {

    private fun entry(ts: Long, addr: String = "AA:BB", kinds: Set<SyncKind> = setOf(SyncKind.TIME)) =
        SyncEntry(
            timestamp = ts, deviceAddress = addr, trigger = SyncTrigger.AUTO,
            kinds = kinds, success = true, errorMessage = null, details = SyncDetails.Empty
        )

    @Test
    fun `serialize then parse round-trips all fields`() {
        val entry = SyncEntry(
            timestamp = 1716671321000L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            trigger = SyncTrigger.MANUAL_SEND_ALARMS,
            kinds = setOf(SyncKind.ALARMS, SyncKind.HOURLY_SIGNAL),
            success = false,
            errorMessage = "watch disconnected",
            details = SyncDetails(
                alarmHashes = listOf("06:30:ALL:true", "09:00:MON,FRI:false"),
                eventCount = null,
                hourlySignalTarget = true,
                timeMs = null,
            )
        )
        val json = serializeSyncHistory(listOf(entry))
        val parsed = parseSyncHistory(json)
        assertEquals(1, parsed.size)
        with(parsed[0]) {
            assertEquals(1716671321000L, timestamp)
            assertEquals("AA:BB:CC:DD:EE:FF", deviceAddress)
            assertEquals(SyncTrigger.MANUAL_SEND_ALARMS, trigger)
            assertEquals(setOf(SyncKind.ALARMS, SyncKind.HOURLY_SIGNAL), kinds)
            assertFalse(success)
            assertEquals("watch disconnected", errorMessage)
            assertEquals(listOf("06:30:ALL:true", "09:00:MON,FRI:false"), details.alarmHashes)
            assertEquals(true, details.hourlySignalTarget)
            assertNull(details.eventCount)
            assertNull(details.timeMs)
        }
    }

    @Test
    fun `parse with corrupt JSON returns empty list`() {
        assertEquals(emptyList<SyncEntry>(), parseSyncHistory("not-valid{{"))
    }

    @Test
    fun `parse with unknown trigger enum value skips entry`() {
        val json = """[{"ts":1000,"addr":"AA","trig":"FUTURE_TRIGGER","kinds":["TIME"],"ok":true,"d":{}}]"""
        assertEquals(emptyList<SyncEntry>(), parseSyncHistory(json))
    }

    @Test
    fun `ring-buffer simulation - 51 inserts keeps newest 50`() {
        val buf = mutableListOf<SyncEntry>()
        for (i in 1..51) {
            buf.add(0, entry(ts = i.toLong()))
            while (buf.size > 50) buf.removeAt(buf.lastIndex)
        }
        assertEquals(50, buf.size)
        assertEquals(51L, buf.first().timestamp)
        assertEquals(2L,  buf.last().timestamp)
    }
}
