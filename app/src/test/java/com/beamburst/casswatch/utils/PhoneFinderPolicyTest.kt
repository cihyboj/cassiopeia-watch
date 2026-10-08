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
}
