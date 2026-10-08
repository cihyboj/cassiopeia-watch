package com.beamburst.casswatch.ui.alarms

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AlarmOccurrenceTest {
    private val utc = ZoneId.of("UTC")
    private fun clock(time: String) = Clock.fixed(Instant.parse(time), utc)
    private fun state(date: String? = null, fired: Long? = null) = AlarmState(
        listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake", fired, date)), mapOf(0 to emptySet()), true)

    @Test fun `tomorrow at seven created tonight survives minute tick`() {
        val migrated = reconcileAlarmState(state(), clock("2026-10-08T23:00:00Z"), utc)
        assertEquals("2026-10-09", migrated.alarms[0].oneShotLocalDate)
        assertNull(reconcileAlarmState(migrated, clock("2026-10-08T23:01:00Z"), utc).alarms[0].firedAt)
    }
    @Test fun `expiry is persisted and backward clock does not unconsume`() {
        val expired = reconcileAlarmState(state("2026-10-09"), clock("2026-10-09T07:01:00Z"), utc)
        assertNotNull(expired.alarms[0].firedAt)
        assertEquals(expired, reconcileAlarmState(expired, clock("2026-10-08T06:00:00Z"), utc))
    }
    @Test fun `name only edit preserves occurrence and consumption`() {
        val original = state("2026-10-09", 1)
        val edited = editAlarmState(original, 0, original.alarms[0].copy(name = "New"), emptySet(), false, clock("2026-10-10T23:00:00Z"), utc)
        assertEquals("2026-10-09", edited.alarms[0].oneShotLocalDate)
        assertEquals(1L, edited.alarms[0].firedAt)
    }
    @Test fun `reenable and time change rearm consumed alarm`() {
        val original = state("2026-10-09", 1)
        for ((alarm, reenable) in listOf(original.alarms[0] to true, original.alarms[0].copy(hour = 8) to false)) {
            val edited = editAlarmState(original, 0, alarm, emptySet(), reenable, clock("2026-10-10T23:00:00Z"), utc)
            assertNull(edited.alarms[0].firedAt)
            assertEquals("2026-10-11", edited.alarms[0].oneShotLocalDate)
        }
    }
    @Test fun `legacy consumed alarm stays consumed without a date`() {
        assertEquals(state(fired = 1), reconcileAlarmState(state(fired = 1), clock("2026-10-08T23:00:00Z"), utc))
    }
    @Test fun `gap advances and overlap chooses earlier offset`() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), AlarmOccurrence(LocalDate.parse("2026-03-29"), 2, 30).resolveInstant(warsaw))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), AlarmOccurrence(LocalDate.parse("2026-10-25"), 2, 30).resolveInstant(warsaw))
    }
    @Test fun `zone change retains wall clock date and time`() {
        val alarm = AlarmOccurrence(LocalDate.parse("2026-10-09"), 7, 0)
        assertEquals(Instant.parse("2026-10-09T05:00:00Z"), alarm.resolveInstant(ZoneId.of("Europe/Warsaw")))
        assertEquals(Instant.parse("2026-10-09T07:00:00Z"), alarm.resolveInstant(utc))
    }
    @Test fun `switch into one time arms and switching back clears consumption`() {
        val daily = state().copy(days = mapOf(0 to AlarmSchedulePlanner.AllDays))
        val armed = editAlarmState(daily, 0, daily.alarms[0], emptySet(), false, clock("2026-10-08T23:00:00Z"), utc)
        assertEquals("2026-10-09", armed.alarms[0].oneShotLocalDate)
        val consumed = reconcileAlarmState(armed, clock("2026-10-09T08:00:00Z"), utc)
        val recurring = editAlarmState(consumed, 0, consumed.alarms[0], AlarmSchedulePlanner.AllDays, false, clock("2026-10-09T08:00:00Z"), utc)
        assertNull(recurring.alarms[0].firedAt)
        assertNull(recurring.alarms[0].oneShotLocalDate)
    }
    @Test fun `successful sync retains consumed marker and does not acknowledge a concurrent edit`() {
        val consumed = state("2026-10-09", 123)
        assertEquals(123L, alarmStateAfterSuccessfulSync(consumed, consumed).alarms[0].firedAt)
        assertFalse(alarmStateAfterSuccessfulSync(consumed, consumed).dirty)
        val edited = consumed.copy(alarms = listOf(consumed.alarms[0].copy(name = "New")))
        assertEquals(edited, alarmStateAfterSuccessfulSync(edited, consumed))
        assertTrue(alarmStateAfterSuccessfulSync(edited, consumed).dirty)
    }
}
