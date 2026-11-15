package com.beamburst.casswatch.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLinkStateResetTest {
    @Suppress("unused")
    private open class FakeHandlerBase {
        private val LOCK = Any()
        private var servicesDiscovered = true
        private var deviceNotSupported = true
        private var serviceDiscoveryRequested = true
        var connected = false
        private var ready = true

        fun flags() = listOf(servicesDiscovered, serviceDiscoveryRequested, ready, deviceNotSupported)
    }

    private class FakeGattCallback : FakeHandlerBase()

    @Test
    fun `clears stale discovery flags on a closed link, including inherited fields`() {
        val handler = FakeGattCallback()

        assertTrue(BleLinkStateReset.resetFlags(handler))
        assertTrue(handler.flags().none { it })
    }

    @Test
    fun `leaves a live link untouched`() {
        val handler = FakeGattCallback().apply { connected = true }

        assertFalse(BleLinkStateReset.resetFlags(handler))
        assertTrue(handler.flags().all { it })
    }
}
