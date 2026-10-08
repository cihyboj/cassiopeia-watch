package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.data.local.reconcileConnectedSettings
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchConnectCoordinatorTest {
    private val watchA = "AA:BB:CC:DD:EE:01"
    private val watchB = "AA:BB:CC:DD:EE:02"

    /** Transport state, setup, initialization, event delivery and abort are independent. */
    private class Transport {
        val session = WatchConnectionSession()
        val handler = WatchConnectionEvents(session)
        var connected = false
        var pending: String? = null
        var initialization = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        var cancellations = 0
        var teardowns = 0
        var uiDisconnects = 0
        val history = mutableListOf<String>()
        val settingsWritten = mutableListOf<Settings>()
        var onStatus: (() -> Unit)? = null
        var onWait: (() -> Unit)? = null
        var onSettingsWrite: (() -> Unit)? = null

        @Suppress("UNCHECKED_CAST")
        val api = Proxy.newProxyInstance(
            IGShockAPI::class.java.classLoader,
            arrayOf(IGShockAPI::class.java),
        ) { _, method, args ->
            when (method.name) {
                "isConnected" -> {
                    val result = connected
                    onStatus?.also { onStatus = null }?.invoke()
                    result
                }
                "waitForConnection" -> {
                    val wait: suspend () -> Unit = {
                        if (!connected && pending == null) {
                            pending = args!![0] as String
                            attempts += pending!!
                        }
                        onWait?.invoke()
                        initialization.await()
                    }
                    wait.startCoroutineUninterceptedOrReturn(args!![1] as Continuation<Unit>)
                }
                "disconnect" -> {
                    connected = false
                    pending = null
                    initialization = CompletableDeferred()
                    cancellations++
                    Unit
                }
                "setSettings" -> { settingsWritten += args!![0] as Settings; onSettingsWrite?.invoke(); Unit }
                else -> error("Unexpected API call: ${method.name}")
            }
        } as IGShockAPI
        val coordinator = WatchConnectCoordinator(api, session)

        fun setup(address: String, device: Any): WatchConnectionSession.Ready? =
            handler.setup(address, device)

        fun initialized() { initialization.complete(Unit) }

        suspend fun connectReady(address: String, device: Any = Any()): WatchConnectionSession.Ready = coroutineScope {
            val attempt = async(start = CoroutineStart.UNDISPATCHED) { coordinator.connect(address) }
            connected = true
            val ready = setup(address, device)!!
            initialized()
            assertTrue(attempt.await())
            ready
        }

        suspend fun disconnectEvent(device: Any) {
            val token = handler.disconnectToken(device) ?: return
            coordinator.handleDisconnect {
                handler.disconnect(token, connected) { ended ->
                    // These are the same effects the production MainEventHandler supplies.
                    teardowns++
                    uiDisconnects++
                    history += ended.address
                }
            }
        }
    }

    @Test fun `CONNECTED before identity retry awaits readiness`() = runBlocking {
        val t = Transport()
        t.session.begin(t.session.state.value, watchA)
        t.connected = true
        val retry = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchA) }
        assertFalse(retry.isCompleted)
        t.setup(watchA, Any())
        assertTrue(retry.await())
        assertEquals(watchA, t.session.address)
        assertEquals(0, t.cancellations)
    }

    @Test fun `setup between connectivity check and library shortcut is retained`() = runBlocking {
        val t = Transport()
        t.session.begin(t.session.state.value, watchA)
        t.onStatus = {
            t.connected = true
            t.setup(watchA, Any())
            t.initialized()
        }
        assertTrue(t.coordinator.connect(watchA, timeoutMs = 100))
        assertEquals(watchA, t.session.address)
        assertTrue(t.attempts.isEmpty())
    }

    @Test fun `setup alone does not finish an owned attempt before initialization`() = runBlocking {
        val t = Transport()
        val attempt = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchA) }
        assertEquals(watchA, t.pending)
        t.connected = true
        t.setup(watchA, Any())
        assertFalse(attempt.isCompleted)
        t.initialized()
        assertTrue(attempt.await())
    }

    @Test fun `late disconnect A after B has no teardown UI or history effects`() = runBlocking {
        val t = Transport()
        val a = Any()
        val first = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchA) }
        t.connected = true
        t.setup(watchA, a)
        t.initialized()
        assertTrue(first.await())
        val b = Any()
        val next = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchB, takeover = true) }
        t.connected = true
        val readyB = t.setup(watchB, b)
        t.initialized()
        assertTrue(next.await())
        // Address/generation alone rejects the old callback even if transport is down.
        t.connected = false
        t.disconnectEvent(a)
        assertEquals(readyB, t.session.ready)
        assertEquals(0, t.teardowns)
        assertEquals(0, t.uiDisconnects)
        assertTrue(t.history.isEmpty())
        t.disconnectEvent(b)
        assertNull(t.session.ready)
        assertEquals(1, t.teardowns)
        assertEquals(1, t.uiDisconnects)
        assertEquals(listOf(watchB), t.history)
    }

    @Test fun `same address reconnect rejects disconnect from previous generation`() = runBlocking {
        val t = Transport()
        val oldDevice = Any()
        val old = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchA) }
        t.connected = true
        val oldReady = t.setup(watchA, oldDevice)!!
        t.initialized()
        assertTrue(old.await())
        t.coordinator.disconnect(watchA)
        val next = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchA) }
        t.connected = true
        val ready = t.setup(watchA, Any())!!
        t.initialized()
        assertTrue(next.await())
        assertTrue(ready.generation > oldReady.generation)
        t.connected = false
        t.disconnectEvent(oldDevice)
        assertEquals(ready, t.session.ready)
        assertEquals(0, t.teardowns)
        assertEquals(0, t.uiDisconnects)
    }

    @Test fun `reused device late disconnect cannot tear down live reconnect`() = runBlocking {
        val t = Transport()
        val device = Any()
        t.connectReady(watchA, device)
        t.coordinator.disconnect(watchA)
        val ready = t.connectReady(watchA, device)
        t.disconnectEvent(device)
        assertEquals(ready, t.session.ready)
        assertEquals(0, t.teardowns)
    }

    @Test fun `timeout cancels pending A before takeover B starts`() = runBlocking {
        val t = Transport()
        val first = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchA, timeoutMs = 30) }
        val next = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchB, takeover = true) }
        assertEquals(listOf(watchA), t.attempts)
        assertFalse(first.await())
        // Await next's entry without relying on a wall-clock sleep.
        while (t.pending != watchB) kotlinx.coroutines.yield()
        assertEquals(1, t.cancellations)
        assertNull(t.setup(watchA, Any())) // cancelled setup cannot label B as A
        t.connected = true
        t.setup(watchB, Any())
        t.initialized()
        assertTrue(next.await())
        assertEquals(listOf(watchA, watchB), t.attempts)
    }

    @Test fun `caller cancellation aborts BLE before releasing ownership`() = runBlocking {
        val t = Transport()
        val first = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchA) }
        first.cancel()
        first.join()
        assertEquals(1, t.cancellations)
        assertNull(t.pending)
        assertNull(t.setup(watchA, Any()))
    }

    @Test fun `presence A with B selected reconciles A and preserves B dirty settings`() = runBlocking {
        val t = Transport()
        val selected = watchB
        val settings = mapOf(watchA to Settings(), watchB to Settings())
        val dirty = mutableSetOf(watchA, watchB)
        val attempt = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchA) }
        t.connected = true
        val ready = t.setup(watchA, Any())!!
        t.initialized()
        assertTrue(attempt.await())
        t.coordinator.withSession(ready) { check ->
            reconcileConnectedSettings(t.api, check, settings[ready.address], ready.address in dirty,
                save = { error("Dirty cached settings should be sent") },
                clearDirty = { dirty.remove(ready.address) })
        }
        assertEquals(listOf(settings[watchA]), t.settingsWritten)
        assertTrue(selected in dirty)
        assertFalse(watchA in dirty)
        assertEquals(watchA, t.session.address)
    }

    @Test fun `manual mismatch refuses visibly and sends no selected data`() = runBlocking {
        val t = Transport()
        t.connectReady(watchA)
        var refused = 0
        val guard = WatchWriteGuard({ watchB }, t.session, t.coordinator) { refused++ }
        assertNull(guard.capture())
        assertEquals(1, refused)
        assertTrue(t.settingsWritten.isEmpty())
    }

    @Test fun `selection changes during suspended manual write prevents send and clean flag`() = runBlocking {
        val t = Transport()
        t.connectReady(watchA)
        var selected = watchA
        var refused = 0
        var dirty = true
        val guard = WatchWriteGuard({ selected }, t.session, t.coordinator) { refused++ }
        val ready = guard.capture()!!
        val continueWrite = CompletableDeferred<Unit>()
        val write = async(start = CoroutineStart.UNDISPATCHED) {
            guard.run(ready) { check ->
                continueWrite.await()
                check()
                t.api.setSettings(Settings())
                dirty = false
            }
        }
        selected = watchB
        continueWrite.complete(Unit)
        write.await()
        assertEquals(1, refused)
        assertTrue(dirty)
        assertTrue(t.settingsWritten.isEmpty())
    }

    @Test fun `takeover waits for complete manual write lifecycle`() = runBlocking {
        val t = Transport()
        val ready = t.connectReady(watchA)
        val finish = CompletableDeferred<Unit>()
        val write = async(start = CoroutineStart.UNDISPATCHED) {
            t.coordinator.withSession(ready, { watchA }) { check ->
                finish.await()
                check()
                t.api.setSettings(Settings())
            }
        }
        val takeover = async(start = CoroutineStart.UNDISPATCHED) { t.coordinator.connect(watchB, takeover = true) }
        assertEquals(0, t.cancellations)
        finish.complete(Unit)
        write.await()
        while (t.pending != watchB) kotlinx.coroutines.yield()
        assertEquals(1, t.settingsWritten.size)
        t.connected = true
        t.setup(watchB, Any())
        t.initialized()
        assertTrue(takeover.await())
    }

    @Test fun `disconnect during reconciliation keeps the dirty flag`() = runBlocking {
        val t = Transport()
        val ready = t.connectReady(watchA)
        var dirty = true
        t.onSettingsWrite = { t.connected = false }
        runCatching {
            t.coordinator.withSession(ready) { check ->
                reconcileConnectedSettings(t.api, check, Settings(), dirty,
                    save = { error("Unexpected read") }, clearDirty = { dirty = false })
            }
        }
        assertTrue(dirty)
        assertEquals(1, t.settingsWritten.size)
    }

    @Test fun `manual token from previous connection generation is refused`() = runBlocking {
        val t = Transport()
        t.connectReady(watchA)
        var refused = 0
        val guard = WatchWriteGuard({ watchA }, t.session, t.coordinator) { refused++ }
        val old = guard.capture()!!
        t.coordinator.disconnect(watchA)
        t.connectReady(watchA)
        guard.run(old) { t.api.setSettings(Settings()) }
        assertEquals(1, refused)
        assertTrue(t.settingsWritten.isEmpty())
    }
}
