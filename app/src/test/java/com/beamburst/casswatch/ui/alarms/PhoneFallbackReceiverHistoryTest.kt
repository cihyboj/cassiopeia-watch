package com.beamburst.casswatch.ui.alarms

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PhoneFallbackReceiverHistoryTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val key = AlarmKey(address, 0)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private val alarm = AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake")
    private val payload = WatchAlarmPayload(address, 7, 0, true, true, zone.id, 7200, now)
    private fun confirms(payloads: Map<Int, WatchAlarmPayload>, date: String = "2026-10-09", zone: ZoneId = this.zone) =
        watchHasOccurrence(key, alarm, java.time.LocalDate.parse(date).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(), payloads, now, zone)

    @Test fun `daily payload confirms tomorrow and subsequent days until superseded`() {
        assertTrue(confirms(mapOf(0 to payload)))
        assertTrue(confirms(mapOf(0 to payload), "2026-10-10"))
        assertFalse(confirms(mapOf(0 to payload.copy(enabled = false))))
    }
    @Test fun `another watch another slot and missing payload never confirm`() {
        assertFalse(confirms(mapOf(0 to payload.copy(address = "AA:BB:CC:DD:EE:02"))))
        assertFalse(confirms(mapOf(1 to payload)))
        assertFalse(confirms(emptyMap()))
    }
    @Test fun `changed phone zone offset or future receipt cannot confirm`() {
        assertFalse(confirms(mapOf(0 to payload), zone = ZoneId.of("Europe/London")))
        assertFalse(confirms(mapOf(0 to payload.copy(utcOffsetSeconds = 3600))))
        assertFalse(confirms(mapOf(0 to payload.copy(syncedAt = now + 1))))
    }
    @Test fun `changed time or non daily disabled payload cannot confirm`() {
        assertFalse(confirms(mapOf(0 to payload.copy(hour = 8))))
        assertFalse(confirms(mapOf(0 to payload.copy(daily = false))))
        assertFalse(confirms(mapOf(0 to payload.copy(enabled = false))))
    }
}
