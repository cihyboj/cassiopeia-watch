package com.beamburst.casswatch.ui.time

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTimeCapabilityTest {
    @Test
    fun `watch without world cities never reads home time`() {
        assertFalse(canReadHomeTime(hasHomeTime = true, hasWorldCities = false))
    }

    @Test
    fun `watch without home time never reads it`() {
        assertFalse(canReadHomeTime(hasHomeTime = false, hasWorldCities = true))
    }

    @Test
    fun `watch with world cities and home time reads it`() {
        assertTrue(canReadHomeTime(hasHomeTime = true, hasWorldCities = true))
    }
}
