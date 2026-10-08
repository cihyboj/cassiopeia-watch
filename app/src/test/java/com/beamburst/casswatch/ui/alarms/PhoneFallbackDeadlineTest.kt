package com.beamburst.casswatch.ui.alarms

import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test

class PhoneFallbackDeadlineTest {
    @Test fun `resolution crossing occurrence deadline cannot start activity`() {
        var now = 999L
        var launched = false
        val result = runCatching { handoffBeforeDeadline(1_000, { now }, { true }, { now = 1_000 }, { launched = true }) }
        assertTrue(result.isFailure)
        assertFalse(launched)
    }
    @Test fun `resolution exhausting receiver budget cannot start activity`() {
        var remaining = true
        var launched = false
        val result = runCatching { handoffBeforeDeadline(1_000, { 999 }, { remaining }, { remaining = false }, { launched = true }) }
        assertTrue(result.isFailure)
        assertFalse(launched)
    }
    @Test fun `resolved timely handoff is requested once`() {
        var launches = 0
        handoffBeforeDeadline(1_000, { 999 }, { true }, {}, { launches++ })
        assertEquals(1, launches)
    }
    @Test fun `routine and uncertain recovery notifications use low importance`() {
        assertEquals(NotificationManager.IMPORTANCE_LOW, fallbackNotificationImportance(false))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, fallbackNotificationImportance(true))
    }
}
