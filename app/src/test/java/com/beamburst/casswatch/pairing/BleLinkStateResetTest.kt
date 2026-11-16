package com.beamburst.casswatch.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    // Mirrors Nordic BleManager -> GShockManagerImpl, wrapped by IGShockManager's `by` delegate.
    @Suppress("unused")
    private open class FakeBleManager { val requestHandler = FakeGattCallback() }
    private class FakeManagerImpl : FakeBleManager()
    private interface FakeGShock
    private class FakeWrapper(val impl: FakeManagerImpl = FakeManagerImpl()) : FakeGShock

    @Test
    fun `finds the Nordic handler through the library's delegating wrapper`() {
        val wrapper = FakeWrapper()

        assertSame(wrapper.impl.requestHandler, BleLinkStateReset.requestHandlerOf(wrapper))
    }

    @Test
    fun `clears stale discovery flags on a closed link, including inherited fields`() {
        val handler = FakeGattCallback()

        assertTrue(BleLinkStateReset.resetFlags(handler) { false })
        assertTrue(handler.flags().none { it })
    }

    @Test
    fun `leaves a live link untouched`() {
        val handler = FakeGattCallback().apply { connected = true }

        assertFalse(BleLinkStateReset.resetFlags(handler) { false })
        assertTrue(handler.flags().all { it })
    }

    @Test
    fun `leaves discovery flags untouched while a new connection is starting`() {
        val handler = FakeGattCallback()

        assertFalse(BleLinkStateReset.resetFlags(handler) { true })
        assertFalse(handler.connected)
        assertTrue(handler.flags().all { it })
    }
}
