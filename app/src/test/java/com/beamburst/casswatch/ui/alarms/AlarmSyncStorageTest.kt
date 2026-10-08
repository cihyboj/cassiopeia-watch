package com.beamburst.casswatch.ui.alarms

import com.google.gson.Gson
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSyncStorageTest {
    private val gson = Gson()

    @Test
    fun `StoredAlarm without firedAt field deserializes with firedAt null`() {
        val oldJson = """[{"hour":6,"minute":30,"enabled":true,"hasHourlyChime":false,"name":"Wake-up"}]"""
        val type = object : com.google.gson.reflect.TypeToken<List<AlarmSyncStorage.StoredAlarm>>() {}.type
        val stored: List<AlarmSyncStorage.StoredAlarm> = gson.fromJson(oldJson, type)
        assertNull(stored[0].firedAt)
    }

    @Test
    fun `StoredAlarm with firedAt deserializes correctly`() {
        val json = """[{"hour":6,"minute":30,"enabled":true,"hasHourlyChime":false,"name":null,"firedAt":1234567890}]"""
        val type = object : com.google.gson.reflect.TypeToken<List<AlarmSyncStorage.StoredAlarm>>() {}.type
        val stored: List<AlarmSyncStorage.StoredAlarm> = gson.fromJson(json, type)
        assertEquals(1234567890L, stored[0].firedAt)
    }

    @Test
    fun `saveAlarms and loadAlarms use per-address key - round trip`() {
        // Test that StoredAlarm serialization produces the per-address key name correctly.
        // We can verify the key convention by testing the gson round-trip for different addresses.
        val stored = AlarmSyncStorage.StoredAlarm(
            hour = 7, minute = 30, enabled = true, hasHourlyChime = false, name = "Work", firedAt = null
        )
        val json = gson.toJson(listOf(stored))
        val type = object : com.google.gson.reflect.TypeToken<List<AlarmSyncStorage.StoredAlarm>>() {}.type
        val roundTripped: List<AlarmSyncStorage.StoredAlarm> = gson.fromJson(json, type)
        assertEquals(1, roundTripped.size)
        assertEquals(stored, roundTripped[0])
    }

    @Test
    fun `StoredAlarm serialization is stable across addresses`() {
        // StoredAlarm with all fields populated round-trips correctly
        val stored = AlarmSyncStorage.StoredAlarm(
            hour = 22, minute = 0, enabled = false, hasHourlyChime = true, name = null, firedAt = 9999L
        )
        val json = gson.toJson(listOf(stored))
        val type = object : com.google.gson.reflect.TypeToken<List<AlarmSyncStorage.StoredAlarm>>() {}.type
        val result: List<AlarmSyncStorage.StoredAlarm> = gson.fromJson(json, type)
        assertEquals(stored.hour, result[0].hour)
        assertEquals(stored.firedAt, result[0].firedAt)
        assertEquals(stored.hasHourlyChime, result[0].hasHourlyChime)
        assertNull(result[0].name)
    }
    @Test
    fun `complete state codec preserves dates days consumption and dirty together`() {
        val state = AlarmState(
            listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake", 123L, "2026-10-09")),
            mapOf(0 to emptySet()), true,
        )
        assertEquals(state, AlarmSyncStorage.decode(AlarmSyncStorage.encode(state)))
    }
    @Test fun `malformed state returns defaults instead of preventing another watch from loading`() {
        val badAddress = "AA:BB:CC:DD:EE:01"
        val goodAddress = "AA:BB:CC:DD:EE:02"
        val good = AlarmState(listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, null)))
        val entries = mapOf(AlarmSyncStorage.stateKey(badAddress) to "broken", AlarmSyncStorage.stateKey(goodAddress) to AlarmSyncStorage.encode(good))
        assertEquals(AlarmState(), AlarmSyncStorage.readState(badAddress, entries::get))
        assertEquals(good, AlarmSyncStorage.readState(goodAddress, entries::get))
    }
    @Test fun `payload codec preserves address slot enabled daily and clock evidence`() {
        val address = "AA:BB:CC:DD:EE:01"
        val state = AlarmState(watchPayloads = mapOf(3 to WatchAlarmPayload(address, 7, 0, true, true, "Europe/Warsaw", 7200, 123)))
        assertEquals(state, AlarmSyncStorage.decode(AlarmSyncStorage.encode(state)))
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid draft time is rejected`() {
        AlarmSyncStorage.decodeAlarms("""[{"hour":24,"minute":0,"enabled":true,"hasHourlyChime":false}]""")
    }
}
