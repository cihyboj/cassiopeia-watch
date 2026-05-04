package com.beamburst.casswatch.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentWatchSelectorTest {

    private fun makeFlow(initial: String?): MutableStateFlow<String?> = MutableStateFlow(initial)

    @Test
    fun `currentAddress returns null when no address set`() {
        val flow = makeFlow(null)
        assertNull(flow.value)
    }

    @Test
    fun `selecting an address updates flow value`() {
        val flow = makeFlow(null)
        flow.value = "AA:BB:CC:DD:EE:FF"
        assertEquals("AA:BB:CC:DD:EE:FF", flow.value)
    }

    @Test
    fun `clearing address resets flow to null`() {
        val flow = makeFlow("AA:BB:CC:DD:EE:FF")
        flow.value = null
        assertNull(flow.value)
    }

    @Test
    fun `LAST_DEVICE_ADDRESS_KEY is correct`() {
        assertEquals("LastDeviceAddress", CurrentWatchSelector.LAST_DEVICE_ADDRESS_KEY)
    }
}
