package com.beamburst.casswatch.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFinderPolicyTest {
    @Test
    fun `watch trigger starts when idle and stops when ringing`() {
        assertFalse(shouldStopPhoneFinder(false))
        assertTrue(shouldStopPhoneFinder(true))
    }

    @Test
    fun `late permission grant shows Stop only while still ringing`() {
        assertFalse(shouldShowPhoneFinderStopNotification(isRinging = true, notificationsPermitted = false))
        assertTrue(shouldShowPhoneFinderStopNotification(isRinging = true, notificationsPermitted = true))
        assertFalse(shouldShowPhoneFinderStopNotification(isRinging = false, notificationsPermitted = true))
        assertFalse(shouldShowPhoneFinderStopNotification(isRinging = false, notificationsPermitted = false))
    }
}
