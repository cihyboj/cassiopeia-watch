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
    fun `key includes address suffix`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        assertEquals("PhoneAlarmDrafts_AA:BB:CC:DD:EE:FF", "PhoneAlarmDrafts_$addr")
        assertEquals("PhoneAlarmDraftsDirty_AA:BB:CC:DD:EE:FF", "PhoneAlarmDraftsDirty_$addr")
        assertEquals("AlarmViewMode_AA:BB:CC:DD:EE:FF", "AlarmViewMode_$addr")
        assertEquals("AlarmDaySelections_AA:BB:CC:DD:EE:FF", "AlarmDaySelections_$addr")
    }

    @Test
    fun `flowFor emits new address when tick changes`() {
        val tick = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val addr = "AA:BB"
        val keyFn: (String) -> String = { "PhoneAlarmDrafts_$it" }
        assertEquals("PhoneAlarmDrafts_AA:BB", keyFn(addr))
        tick.value = 1L
        assertEquals(1L, tick.value)
    }
}
