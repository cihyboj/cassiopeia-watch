package com.beamburst.casswatch.data.local

import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchConnectionTrackerTest {

    @Test
    fun `ConnectionState has four values`() {
        val values = ConnectionState.entries
        assertEquals(4, values.size)
        assertTrue(ConnectionState.CONNECTED in values)
        assertTrue(ConnectionState.CONNECTING in values)
        assertTrue(ConnectionState.DISCONNECTED in values)
        assertTrue(ConnectionState.ERROR in values)
    }

    @Test
    fun `mark updates state map for address`() {
        val states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
        val addr = "AA:BB"
        states.value = states.value + (addr to ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, states.value[addr])
    }

    @Test
    fun `marking null address is a no-op`() {
        val states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
        val addr: String? = null
        if (addr != null) states.value = states.value + (addr to ConnectionState.CONNECTED)
        assertEquals(emptyMap<String, ConnectionState>(), states.value)
    }

    @Test
    fun `disconnecting address updates state to DISCONNECTED`() {
        val states = MutableStateFlow(mapOf("AA:BB" to ConnectionState.CONNECTED))
        states.value = states.value + ("AA:BB" to ConnectionState.DISCONNECTED)
        assertEquals(ConnectionState.DISCONNECTED, states.value["AA:BB"])
    }

    @Test
    fun `unknown address lookup returns null`() {
        val states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
        assertNull(states.value["ZZ:ZZ"])
    }
}
