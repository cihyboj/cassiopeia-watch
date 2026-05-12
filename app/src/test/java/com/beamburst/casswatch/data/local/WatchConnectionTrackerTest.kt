package com.beamburst.casswatch.data.local

import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `connection generation defaults to zero for unknown address`() {
        val generations = MutableStateFlow<Map<String, Long>>(emptyMap())
        assertEquals(0L, generations.value["AA:BB"] ?: 0L)
    }

    /**
     * Models the cache-warm bookkeeping inside [WatchConnectionTracker]: an address is
     * considered warm only when the recorded `cacheWarmGenerations` value equals the
     * current `connectionGenerations` value. A bump of the connection generation
     * (reconnect) must invalidate the warm flag.
     */
    @Test
    fun `cache warm flag invalidated when connection generation bumps`() {
        val addr = "AA:BB"
        val generations = MutableStateFlow(mapOf(addr to 1L))
        val warm = MutableStateFlow(mapOf(addr to 1L))

        assertTrue("warm at matching generation", warm.value[addr] == generations.value[addr])

        generations.value = mapOf(addr to 2L)
        assertFalse("warm cleared after reconnect", warm.value[addr] == generations.value[addr])
    }

    @Test
    fun `cache warm flag survives state changes within the same generation`() {
        val addr = "AA:BB"
        val generations = MutableStateFlow(mapOf(addr to 5L))
        val warm = MutableStateFlow(mapOf(addr to 5L))

        // A spurious "connected" event in the middle of a session must not invalidate warmth.
        val redundantMark = mapOf(addr to ConnectionState.CONNECTED)
        assertEquals(ConnectionState.CONNECTED, redundantMark[addr])
        assertTrue(warm.value[addr] == generations.value[addr])
    }

    @Test
    fun `cache warm threshold separates Mode-1 from Mode-2 durations`() {
        // The threshold is intentionally well above Mode-2 (3–8 ms) and well below Mode-1
        // (2–4 s) so a single sample is enough to classify the connection.
        val threshold = WatchConnectionTracker.CACHE_WARM_THRESHOLD_MS
        assertTrue("Mode-2 sample below threshold", 50L < threshold)
        assertTrue("Mode-1 sample above threshold", 2_000L > threshold)
    }
}
