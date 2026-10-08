package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.avmedia.gshockapi.IGShockAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchConnectCoordinatorTest {
    private val watchA = "AA:BB:CC:DD:EE:01"
    private val watchB = "AA:BB:CC:DD:EE:02"

    private class FakeApi(val session: WatchConnectionSession) {
        var connected = false
        var connectSucceeds = true
        val attempts = mutableListOf<String>()
        var disconnects = 0

        var onConnection: suspend (String) -> Unit = { address ->
            connected = connectSucceeds
            if (connected) session.established(address)
        }

        @Suppress("UNCHECKED_CAST")
        val api = Proxy.newProxyInstance(
            IGShockAPI::class.java.classLoader,
            arrayOf(IGShockAPI::class.java),
        ) { _, method, args ->
            when (method.name) {
                "isConnected" -> connected
                "waitForConnection" -> {
                    val address = args!![0] as String
                    attempts += address
                    val wait: suspend () -> Unit = { onConnection(address) }
                    wait.startCoroutineUninterceptedOrReturn(args[1] as Continuation<Unit>)
                }
                "disconnect" -> {
                    connected = false
                    session.disconnected()
                    disconnects++
                    Unit
                }
                else -> error("Unexpected API call: ${method.name}")
            }
        } as IGShockAPI
    }

    @Test
    fun `A connected then selecting B does not make connect B succeed`() = runBlocking {
        val session = WatchConnectionSession()
        val fake = FakeApi(session)
        val coordinator = WatchConnectCoordinator(fake.api, session)
        // This is the selected value that CurrentWatchSelector also persists as LastDeviceAddress.
        val selectedAddress = MutableStateFlow(watchA)

        assertTrue(coordinator.connect(selectedAddress.value))
        selectedAddress.value = watchB

        assertFalse(coordinator.connect(selectedAddress.value))
        assertEquals(watchA, session.address)
        assertEquals(listOf(watchA), fake.attempts)
        assertEquals(0, fake.disconnects)
    }

    @Test
    fun `same session uses shortcut and takeover establishes the requested session`() = runBlocking {
        val session = WatchConnectionSession()
        val fake = FakeApi(session)
        val coordinator = WatchConnectCoordinator(fake.api, session)
        assertTrue(coordinator.connect(watchA))
        assertTrue(coordinator.connect(watchA.lowercase().replace(':', '-')))
        assertEquals(1, fake.attempts.size)

        assertTrue(coordinator.connect(watchB, takeover = true))
        assertEquals(watchB, session.address)
        assertEquals(listOf(watchA, watchB), fake.attempts)
        assertEquals(1, fake.disconnects)
    }

    @Test
    fun `disconnect clears session and unsuccessful API return is not success`() = runBlocking {
        val session = WatchConnectionSession()
        val fake = FakeApi(session)
        val coordinator = WatchConnectCoordinator(fake.api, session)
        assertTrue(coordinator.connect(watchA))
        fake.api.disconnect()
        assertNull(session.address)

        fake.connectSucceeds = false
        assertFalse(coordinator.connect(watchB))
        assertNull(session.address)
    }

    @Test
    fun `late connection for A cannot be relabeled as requested B`() = runBlocking {
        val session = WatchConnectionSession()
        val fake = FakeApi(session)
        val coordinator = WatchConnectCoordinator(fake.api, session)
        fake.onConnection = { awaitCancellation() }
        assertFalse(coordinator.connect(watchA, timeoutMs = 20))

        // The BLE stack finishes the old attempt while waitForConnection(B) is pending.
        fake.onConnection = {
            fake.connected = true
            session.established(watchA)
        }
        assertFalse(coordinator.connect(watchB))
        assertEquals(watchA, session.address)
        assertEquals(listOf(watchA, watchB), fake.attempts)
    }

    @Test
    fun `stale identity from a missed disconnect is not reused for the next attempt`() = runBlocking {
        val session = WatchConnectionSession()
        val fake = FakeApi(session)
        session.established(watchA)
        fake.onConnection = { fake.connected = true }
        val coordinator = WatchConnectCoordinator(fake.api, session)
        val result = async(start = CoroutineStart.UNDISPATCHED) { coordinator.connect(watchB) }
        assertFalse(result.isCompleted)
        assertNull(session.address)

        session.established(watchB)
        assertTrue(result.await())
    }

    @Test
    fun `late disconnect event for the previous watch keeps the new session`() {
        val session = WatchConnectionSession()
        session.established(watchB)
        session.disconnected(watchA)
        assertEquals(watchB, session.address)
        session.disconnected(watchB)
        assertNull(session.address)
    }

    @Test
    fun `waits for connection event identity after API returns`() = runBlocking {
        val session = WatchConnectionSession()
        val fake = FakeApi(session)
        fake.onConnection = { fake.connected = true }
        val coordinator = WatchConnectCoordinator(fake.api, session)
        val result = async(start = CoroutineStart.UNDISPATCHED) { coordinator.connect(watchA) }
        assertFalse(result.isCompleted)
        assertNull(session.address)

        session.established(watchA)
        assertTrue(result.await())
    }

    @Test
    fun `connected without event identity times out without guessing the address`() = runBlocking {
        val session = WatchConnectionSession()
        val fake = FakeApi(session)
        fake.onConnection = { fake.connected = true }
        val coordinator = WatchConnectCoordinator(fake.api, session)

        assertFalse(coordinator.connect(watchA, timeoutMs = 20))
        assertNull(session.address)
        assertFalse(coordinator.connect(watchB))
        assertEquals(listOf(watchA), fake.attempts)
    }

    @Test
    fun `concurrent requests serialize and the second cannot claim the first connection`() = runBlocking {
        val session = WatchConnectionSession()
        val fake = FakeApi(session)
        val ready = CompletableDeferred<Unit>()
        fake.onConnection = { address ->
            ready.await()
            fake.connected = true
            session.established(address)
        }
        val coordinator = WatchConnectCoordinator(fake.api, session)
        val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.connect(watchA) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.connect(watchB) }
        assertEquals(listOf(watchA), fake.attempts)

        ready.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(listOf(watchA), fake.attempts)
    }
}
