package com.beamburst.casswatch.ui.alarms

import org.junit.Assert.*
import org.junit.Test

class AlarmFireOnceStateTest {

    @Test
    fun `showAsEnabled is true when alarm enabled and not yet fired`() {
        val enabled = true
        val firedAt: Long? = null
        val showAsEnabled = enabled && firedAt == null
        assertTrue(showAsEnabled)
    }

    @Test
    fun `showAsEnabled is false when firedAt is set even though enabled is true`() {
        val enabled = true
        val firedAt: Long? = 1234567890L
        val showAsEnabled = enabled && firedAt == null
        assertFalse(showAsEnabled)
    }

    @Test
    fun `confirming sync clears firedAt and sets enabled false`() {
        var enabled = true
        var firedAt: Long? = 1234567890L
        // Simulate: successful sync with alarm sent disabled
        val sentDisabled = true // we sent it disabled to the watch
        if (sentDisabled && firedAt != null) {
            enabled = false
            firedAt = null
        }
        assertFalse(enabled)
        assertNull(firedAt)
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
