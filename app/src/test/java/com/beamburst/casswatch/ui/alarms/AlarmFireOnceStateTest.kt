package com.beamburst.casswatch.ui.alarms

import org.junit.Assert.*
import org.junit.Test

class AlarmFireOnceStateTest {

    @Test
    fun `showAsEnabled is true when alarm enabled and not yet fired`() {
        val enabled = true
        val firedAt: Long? = null
        val showAsEnabled = alarmIsArmed(enabled, firedAt)
        assertTrue(showAsEnabled)
    }

    @Test
    fun `showAsEnabled is false when firedAt is set even though enabled is true`() {
        val enabled = true
        val firedAt: Long? = 1234567890L
        val showAsEnabled = alarmIsArmed(enabled, firedAt)
        assertFalse(showAsEnabled)
    }

    @Test
    fun `confirming sync retains consumed occurrence`() {
        val consumed = AlarmState(listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, "", firedAt = 123)),
            mapOf(0 to emptySet()), true)
        val confirmed = alarmStateAfterSuccessfulSync(consumed, consumed)
        assertFalse(confirmed.dirty)
        assertEquals(123L, confirmed.alarms[0].firedAt)
        assertFalse(alarmIsArmed(confirmed.alarms[0].enabled, confirmed.alarms[0].firedAt))
    }

    @Test
    fun `alarm hash generation is deterministic`() {
        val hash = alarmHash(hour = 6, minute = 30, days = emptySet(), enabled = true)
        assertEquals("06:30:true", hash)
    }

    @Test
    fun `alarm hash generation omits days`() {
        val hash = alarmHash(hour = 9, minute = 0, days = emptySet(), enabled = false)
        assertEquals("09:00:false", hash)
    }
}
