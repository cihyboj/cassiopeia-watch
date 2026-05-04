package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.SyncKind
import org.avmedia.gshockapi.Alarm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek

class AlarmHashFormatTest {
    @Test
    fun `formats enabled hash with zero-padded time and no day mask`() {
        assertEquals("06:05:true", alarmHash(6, 5, emptySet(), true))
    }

    @Test
    fun `ignores day mask when formatting hash`() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
        assertEquals("09:30:false", alarmHash(9, 30, days, false))
    }

    @Test
    fun `formats watch payload alarm states after scheduling`() {
        val payload = listOf(
            Alarm(hour = 11, minute = 15, enabled = true, hasHourlyChime = false, name = null),
            Alarm(hour = 13, minute = 15, enabled = false, hasHourlyChime = false, name = null),
        )

        assertEquals(listOf("11:15:true", "13:15:false"), alarmHashesForWatchPayload(payload))
    }

    @Test
    fun `alarm sync history details include hourly signal target`() {
        val payload = listOf(
            Alarm(hour = 11, minute = 15, enabled = true, hasHourlyChime = false, name = null),
        )

        val details = alarmSyncDetailsForWatchPayload(
            alarms = payload,
            hourlySignalTarget = false,
            actions = listOf("Weekly alarm schedule applied on reconnect"),
        )

        assertEquals(setOf(SyncKind.ALARMS, SyncKind.HOURLY_SIGNAL), alarmSyncHistoryKinds)
        assertEquals(listOf("11:15:true"), details.alarmHashes)
        assertEquals(false, details.hourlySignalTarget)
        assertEquals(listOf("Weekly alarm schedule applied on reconnect"), details.actions)
    }
}
