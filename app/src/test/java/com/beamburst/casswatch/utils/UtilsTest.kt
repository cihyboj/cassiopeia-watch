package com.beamburst.casswatch.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class UtilsTest {

    @Test
    fun `sanitizeMacAddress formats lowercase hex`() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            Utils.sanitizeMacAddress("aabbccddeeff")
        )
    }

    @Test
    fun `sanitizeMacAddress formats separated hex`() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            Utils.sanitizeMacAddress("aa-bb:cc dd-ee_ff")
        )
    }

    @Test
    fun `sanitizeMacAddress returns blank for malformed input`() {
        assertEquals("", Utils.sanitizeMacAddress("AA:BB"))
        assertEquals("", Utils.sanitizeMacAddress("AA:BB:CC:DD:EE:FF:00"))
    }
}
