package com.beamburst.casswatch.ui.alarms

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class PhoneFallbackPolicyTest {
    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-09T06:58:00Z")
    private val clock = Clock.fixed(now, zone)
    private val fire = Instant.parse("2026-10-09T07:00:00Z").toEpochMilli()
    private val state = AlarmState(listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake", oneShotLocalDate = "2026-10-09")), mapOf(0 to emptySet()))

    @Test fun `two watches on same slot have different identities and addresses normalize`() {
        assertNotEquals(AlarmKey(a, 0).uri, AlarmKey(b, 0).uri)
        assertEquals(AlarmKey(a, 0), AlarmKey.from("aabbccddee01", 0))
    }
    @Test fun `missing and invalid address and invalid slot are rejected`() {
        assertNull(AlarmKey.from(null, 0))
        assertNull(AlarmKey.from("invalid", 0))
        assertNull(AlarmKey.from(a, -1))
        assertFalse(validFallbackCallback(AlarmKey(a, 9), listOf(a), state, fire, "07:00:true", clock, zone))
    }
    @Test fun `forgotten watch stale instant and edited time are rejected`() {
        assertFalse(validFallbackCallback(AlarmKey(a, 0), listOf(b), state, fire, "07:00:true", clock, zone))
        assertFalse(validFallbackCallback(AlarmKey(a, 0), listOf(a), state, fire + 86_400_000, "07:00:true", clock, zone))
        assertFalse(validFallbackCallback(AlarmKey(a, 0), listOf(a), state, fire, "08:00:true", clock, zone))
    }
    @Test fun `under five minutes handles immediately but delivery at or after due is ignored`() {
        assertEquals(now.toEpochMilli(), fallbackWakeAt(fire, now.toEpochMilli()))
        assertTrue(validFallbackCallback(AlarmKey(a, 0), listOf(a), state, fire, "07:00:true", clock, zone))
        assertNull(fallbackWakeAt(fire, fire))
        assertNull(fallbackWakeAt(fire, fire + 1))
    }
    @Test fun `early callback and already handed off occurrence are rejected`() {
        assertFalse(validFallbackCallback(AlarmKey(a, 0), listOf(a), state, fire, "07:00:true", Clock.fixed(now.minusSeconds(600), zone), zone))
        val attempted = state.copy(alarms = listOf(state.alarms[0].copy(fallbackAttemptAt = fire)))
        assertFalse(validFallbackCallback(AlarmKey(a, 0), listOf(a), attempted, fire, "07:00:true", clock, zone))
    }
    @Test fun `revoked exact alarm permission exposes unavailable`() {
        assertEquals(FallbackAvailability.UNAVAILABLE, attemptExactFallback({ true }) { throw SecurityException("revoked") })
        assertEquals(FallbackAvailability.UNAVAILABLE, attemptExactFallback({ false }) { fail("must not schedule") })
        assertEquals(FallbackAvailability.SCHEDULED, attemptExactFallback({ true }) {})
    }
    @Test fun `receiver budget includes coroutine dispatch delay and expires before ten seconds`() {
        assertEquals(8000L, alarmReceiverTimeRemaining(1000, 1000))
        assertEquals(2000L, alarmReceiverTimeRemaining(1000, 7000))
        assertNull(alarmReceiverTimeRemaining(1000, 9000))
        assertNull(alarmReceiverTimeRemaining(1000, 12000))
    }
}
