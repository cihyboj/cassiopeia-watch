package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.data.local.reconcileConnectedSettings
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { block() }
    }

    /** Like ProgressEvents, starts, writes and event dispatch are independent of API callers. */
    private class Transport(private val scope: CoroutineScope) {
        val session = WatchConnectionSession()
        val handler get() = coordinator.connectionEvents
        var connected = false
        var pending: String? = null
        var initialization = CompletableDeferred<Unit>()
        var startGate = CompletableDeferred(Unit)
        var writeGate = CompletableDeferred(Unit)
        var response = CompletableDeferred<Settings>()
        var readStarted = CompletableDeferred<Unit>()
        var readCancelled = false
        var onStatus: (() -> Unit)? = null
        var afterWrite: (() -> Unit)? = null
        var closes = 0
        var uiDisconnects = 0
        var initCalls = 0
        val attempts = mutableListOf<String>()
        val history = mutableListOf<String>()
        val failures = mutableListOf<String?>()
        val settingsDelivered = mutableListOf<Pair<String?, Settings>>()
        val initializationEvents = mutableListOf<Long>()

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
                        val address = args!![0] as String
                        val initialized = initialization
                        val gate = startGate
                        attempts += address
                        // This job deliberately survives cancellation of waitForConnection.
                        scope.launch { gate.await(); pending = address }
                        initialized.await()
                        initialize()
                    }
                    wait.startCoroutineUninterceptedOrReturn(args!![1] as Continuation<Unit>)
                }
                "init" -> {
                    val init: suspend () -> Boolean = { initialize(); true }
                    init.startCoroutineUninterceptedOrReturn(args!![0] as Continuation<Boolean>)
                }
                "disconnect" -> {
                    connected = false
                    pending = null
                    initialization = CompletableDeferred()
                    closes++
                    Unit
                }
                "getSettings" -> {
                    val read: suspend () -> Settings = {
                        readStarted.complete(Unit)
                        try { response.await() } finally { readCancelled = !response.isCompleted }
                    }
                    read.startCoroutineUninterceptedOrReturn(args!![0] as Continuation<Settings>)
                }
                "setSettings" -> {
                    val value = args!![0] as Settings
                    val gate = writeGate
                    scope.launch { gate.await(); settingsDelivered += session.address to value }
                    // Queued connection loss is observed only after the setter returns.
                    afterWrite?.also { afterWrite = null }?.let { action -> scope.launch { action() } }
                    Unit
                }
                else -> error("Unexpected API call: ${method.name}")
            }
        } as IGShockAPI
        val coordinator = WatchConnectCoordinator(api, session)

        init {
            // Same central session-ended effects as MainEventHandler: explicit and peer
            // disconnect both pass here, independent of any delayed raw event.
            session.onEnded { ended -> uiDisconnects++; history += ended.address }
        }

        private suspend fun initialize() {
            initCalls++
            yield()
            session.ready?.let { initializationEvents += it.generation }
        }

        suspend fun awaitAttempt(address: String) {
            while (address !in attempts || pending != address) yield()
        }

        suspend fun setup(address: String, device: Any): WatchConnectionSession.Ready? = scope.async {
            yield() // asynchronous ProgressEvents dispatch
            val ready = handler.setup(address, device)
            if (ready == null) coordinator.closeRejectedSetup()
            else if (handler.needsInitialization) {
                coordinator.withSession(ready) { check -> api.guardWrites(check).init() }
            }
            ready
        }.await()

        suspend fun connectReady(address: String, device: Any = Any()): WatchConnectionSession.Ready = coroutineScope {
            val attempt = async { coordinator.connect(address) }
            awaitAttempt(address)
            connected = true
            val ready = setup(address, device)!!
            initialization.complete(Unit)
            assertTrue(attempt.await())
            ready
        }

        suspend fun disconnectEvent(device: Any) = scope.async {
            yield()
            val token = handler.disconnectToken(device) ?: return@async
            coordinator.handleDisconnect { handler.disconnect(token, connected) }
        }.await()

        suspend fun stuckRead(ready: WatchConnectionSession.Ready): Deferred<Result<Settings>> {
            val work = scope.async {
                runCatching { coordinator.withSession(ready) { check -> api.guardWrites(check).getSettings() } }
            }
            readStarted.await()
            return work
        }
    }

    @Test fun `CONNECTED before identity retry awaits readiness`() = scenario {
        val t = Transport(this)
        t.session.begin(t.session.state.value, watchA)
        t.connected = true
        val retry = async { t.coordinator.connect(watchA) }
        yield()
        assertFalse(retry.isCompleted)
        t.setup(watchA, Any())
        assertTrue(retry.await())
        assertTrue(t.attempts.isEmpty())
        assertEquals(0, t.closes)
    }

    @Test fun `setup racing connectivity status is retained`() = scenario {
        val t = Transport(this)
        t.session.begin(t.session.state.value, watchA)
        t.onStatus = { t.connected = true; t.handler.setup(watchA, Any()) }
        assertTrue(t.coordinator.connect(watchA))
        assertEquals(watchA, t.session.address)
        assertTrue(t.attempts.isEmpty())
    }

    @Test fun `new setup between transport check and begin preserves accepted generation`() = scenario {
        val t = Transport(this)
        val old = t.connectReady(watchA)
        t.connected = false
        var accepted: WatchConnectionSession.Ready? = null
        t.onStatus = {
            t.onStatus = {
                t.connected = true
                accepted = t.handler.setup(watchA, Any())
            }
        }
        assertTrue(t.coordinator.connect(watchA))
        assertEquals(accepted, t.session.ready)
        assertTrue(t.session.ready!!.generation > old.generation)
        assertEquals(listOf(watchA), t.attempts)
    }

    @Test fun `delayed rejected setup cleanup preserves replacement session`() = scenario {
        val t = Transport(this)
        val rejectedState = t.session.state.value
        val rejectedGeneration = t.session.lastGeneration
        val ready = t.connectReady(watchB)
        t.coordinator.closeRejectedSetup(rejectedState, rejectedGeneration)
        assertEquals(ready, t.session.ready)
        assertTrue(t.connected)
        assertEquals(0, t.closes)
    }

    @Test fun `owned attempt waits for library initialization after setup`() = scenario {
        val t = Transport(this)
        val attempt = async { t.coordinator.connect(watchA) }
        t.awaitAttempt(watchA)
        t.connected = true
        t.setup(watchA, Any())
        assertFalse(attempt.isCompleted)
        t.initialization.complete(Unit)
        assertTrue(attempt.await())
    }

    @Test fun `never returning read cannot block asynchronous disconnect handling`() = scenario {
        val t = Transport(this)
        val device = Any()
        val ready = t.connectReady(watchA, device)
        val work = t.stuckRead(ready)
        t.connected = false
        t.disconnectEvent(device)
        assertTrue(work.await().isFailure)
        assertTrue(t.readCancelled)
        assertNull(t.session.ready)
        assertEquals(listOf(watchA), t.history)
    }

    @Test fun `never returning read cannot block manual disconnect`() = scenario {
        val t = Transport(this)
        val work = t.stuckRead(t.connectReady(watchA))
        t.coordinator.disconnect(watchA)
        assertTrue(work.await().isFailure)
        assertTrue(t.readCancelled)
        assertEquals(1, t.closes)
        assertEquals(1, t.uiDisconnects)
    }

    @Test fun `takeover cancels stuck read without waiting for it`() = scenario {
        val t = Transport(this)
        val work = t.stuckRead(t.connectReady(watchA))
        val takeover = async { t.coordinator.connect(watchB, takeover = true) }
        t.awaitAttempt(watchB)
        assertTrue(work.await().isFailure)
        assertTrue(t.readCancelled)
        t.connected = true
        t.setup(watchB, Any())
        t.initialization.complete(Unit)
        assertTrue(takeover.await())
        assertEquals(watchB, t.session.address)
    }

    @Test fun `response timeout fails work and releases session serialization`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        val failed = runCatching {
            t.coordinator.withSession(ready) { check -> t.api.guardWrites(check, responseTimeoutMs = 100).getSettings() }
        }
        assertTrue(failed.exceptionOrNull() is WatchResponseTimeoutException)
        assertTrue(t.readCancelled)
        t.response.complete(Settings())
        t.coordinator.withSession(ready) { check -> t.api.guardWrites(check).getSettings() }
        assertEquals(ready, t.session.ready)
    }

    @Test fun `queued operation is cancelled together with active session work`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        val read = t.stuckRead(ready)
        var ran = false
        val queued = async { runCatching { t.coordinator.withSession(ready) { ran = true } } }
        yield()
        t.coordinator.disconnect()
        assertTrue(read.await().isFailure)
        assertTrue(queued.await().isFailure)
        assertFalse(ran)
    }

    @Test fun `autonomous reconnect after observed drop initializes fresh generation`() = scenario {
        val t = Transport(this)
        val device = Any()
        val old = t.connectReady(watchA, device)
        val work = t.stuckRead(old)
        t.connected = false
        t.disconnectEvent(device)
        t.connected = true
        val ready = t.setup(watchA, device)!!
        assertTrue(ready.generation > old.generation)
        assertTrue(work.await().isFailure)
        assertEquals(2, t.initCalls)
        assertEquals(listOf(old.generation, ready.generation), t.initializationEvents)
        assertTrue(runCatching { t.coordinator.withSession(old) {} }.isFailure)
    }

    @Test fun `delayed disconnect after transport reconnect with same device initializes fresh generation`() = scenario {
        val t = Transport(this)
        val device = Any()
        val old = t.connectReady(watchA, device)
        val work = t.stuckRead(old)
        // No down status was observed: the raw callback arrives after transport is up.
        t.connected = true
        t.disconnectEvent(device)
        assertTrue(work.await().isFailure)
        val ready = t.setup(watchA, device)!!
        assertTrue(ready.generation > old.generation)
        assertEquals(2, t.initCalls)
        assertEquals(listOf(old.generation, ready.generation), t.initializationEvents)
        assertEquals(1, t.uiDisconnects)
    }

    @Test fun `new setup device renews Ready even before disconnect dispatch`() = scenario {
        val t = Transport(this)
        val oldDevice = Any()
        val old = t.connectReady(watchA, oldDevice)
        val work = t.stuckRead(old)
        val ready = t.setup(watchA, Any())!!
        assertTrue(ready.generation > old.generation)
        assertTrue(work.await().isFailure)
        assertEquals(2, t.initCalls)
        t.disconnectEvent(oldDevice)
        assertEquals(ready, t.session.ready)
        assertEquals(1, t.uiDisconnects)
    }

    @Test fun `each transport setup advances generation even with same device payload`() = scenario {
        val t = Transport(this)
        val device = Any()
        val ready = t.connectReady(watchA, device)
        assertTrue(t.setup(watchA, device)!!.generation > ready.generation)
        assertEquals(2, t.initCalls)
        assertEquals(1, t.uiDisconnects)
    }

    @Test fun `explicit disconnect emits effects once despite delayed raw callbacks`() = scenario {
        val t = Transport(this)
        val device = Any()
        t.connectReady(watchA, device)
        t.coordinator.disconnect(watchA)
        t.disconnectEvent(device)
        t.disconnectEvent(device)
        assertEquals(1, t.uiDisconnects)
        assertEquals(listOf(watchA), t.history)
    }

    @Test fun `late callback from A cannot end takeover B`() = scenario {
        val t = Transport(this)
        val oldDevice = Any()
        t.connectReady(watchA, oldDevice)
        val next = async { t.coordinator.connect(watchB, takeover = true) }
        t.awaitAttempt(watchB)
        t.connected = true
        val ready = t.setup(watchB, Any())!!
        t.initialization.complete(Unit)
        assertTrue(next.await())
        t.connected = false
        t.disconnectEvent(oldDevice)
        assertEquals(ready, t.session.ready)
        assertEquals(listOf(watchA), t.history)
    }

    @Test fun `detached start after timeout is closed when setup arrives Disconnected`() = scenario {
        val t = Transport(this)
        t.startGate = CompletableDeferred()
        assertFalse(t.coordinator.connect(watchA, timeoutMs = 1_000))
        assertNull(t.pending)
        assertEquals(1, t.closes)
        t.startGate.complete(Unit)
        t.awaitAttempt(watchA)
        t.connected = true
        assertNull(t.setup(watchA, Any()))
        assertFalse(t.connected)
        assertNull(t.session.ready)
        assertEquals(2, t.closes)
        assertEquals(0, t.initCalls)
    }

    @Test fun `manual stop after peer drop rejects autonomous late setup`() = scenario {
        val t = Transport(this)
        val device = Any()
        t.connectReady(watchA, device)
        t.connected = false
        t.disconnectEvent(device)
        t.coordinator.disconnect()
        t.connected = true
        assertNull(t.setup(watchA, device))
        assertFalse(t.connected)
    }

    @Test fun `disconnect during connection initialization cancels attempt`() = scenario {
        val t = Transport(this)
        val result = async { runCatching { t.coordinator.connect(watchA) } }
        t.awaitAttempt(watchA)
        t.connected = true
        val device = Any()
        t.setup(watchA, device)
        t.connected = false
        t.disconnectEvent(device)
        assertTrue(result.await().isFailure)
        assertNull(t.session.ready)
    }

    @Test fun `connection failure history captures Connecting address before cleanup`() = scenario {
        val t = Transport(this)
        val result = async { runCatching { t.coordinator.connect(watchA) } }
        t.awaitAttempt(watchA)
        assertNull(t.session.address)
        t.handler.failed { t.failures += it }
        t.coordinator.disconnect()
        result.await()
        assertEquals(listOf(watchA), t.failures)
    }

    @Test fun `caller cancellation closes its owned attempt`() = scenario {
        val t = Transport(this)
        val result = async { t.coordinator.connect(watchA) }
        t.awaitAttempt(watchA)
        result.cancel()
        result.join()
        assertEquals(1, t.closes)
        assertNull(t.session.ready)
    }

    @Test fun `selected mismatch refuses without writing`() = scenario {
        val t = Transport(this)
        t.connectReady(watchA)
        var refused = 0
        val guard = WatchWriteGuard({ watchB }, t.session, t.coordinator) { refused++ }
        assertNull(guard.capture())
        assertEquals(1, refused)
        assertTrue(t.settingsDelivered.isEmpty())
    }

    @Test fun `selection change during helper read fences write and clean flag`() = scenario {
        val t = Transport(this)
        t.connectReady(watchA)
        var selected = watchA
        var dirty = true
        var refused = 0
        val guard = WatchWriteGuard({ selected }, t.session, t.coordinator) { refused++ }
        val ready = guard.capture()!!
        val work = async {
            guard.runApi(ready, t.api) { api, confirm ->
                api.getSettings()
                api.setSettings(Settings())
                confirm()
                dirty = false
            }
        }
        t.readStarted.await()
        selected = watchB
        t.response.complete(Settings())
        work.await()
        assertEquals(1, refused)
        assertTrue(dirty)
        assertTrue(t.settingsDelivered.isEmpty())
    }

    @Test fun `disconnect queued immediately after setter preserves dirty state`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        var dirty = true
        t.afterWrite = { t.connected = false; t.session.ended(ready) }
        runCatching {
            t.coordinator.withSession(ready) { check ->
                reconcileConnectedSettings(t.api, check, Settings(), dirty,
                    save = { error("Unexpected read") }, clearDirty = { dirty = false })
            }
        }
        assertTrue(dirty)
        assertEquals(1, t.uiDisconnects)
    }

    @Test fun `presence reconciles connected address and preserves another watch dirty data`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        val settings = Settings()
        val dirty = mutableSetOf(watchA, watchB)
        t.coordinator.withSession(ready) { check ->
            reconcileConnectedSettings(t.api, check, settings, true,
                save = { error("Unexpected read") }, clearDirty = { dirty.remove(ready.address) })
        }
        assertEquals(listOf(watchA to settings), t.settingsDelivered)
        assertTrue(watchB in dirty)
        assertFalse(watchA in dirty)
    }

    @Test fun `automatic obsolete read ends without refusal feedback`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        var refused = 0
        var updated = false
        val guard = WatchWriteGuard({ watchA }, t.session, t.coordinator) { refused++ }
        val read = async {
            guard.readApi(ready, t.api) { api, confirm ->
                api.getSettings()
                confirm()
                updated = true
            }
        }
        t.readStarted.await()
        t.coordinator.disconnect()
        read.await()
        assertFalse(updated)
        assertEquals(0, refused)
        assertTrue(t.readCancelled)
    }

    @Test fun `manual guard rejects old generation after same watch reconnect`() = scenario {
        val t = Transport(this)
        val old = t.connectReady(watchA)
        var refused = 0
        val guard = WatchWriteGuard({ watchA }, t.session, t.coordinator) { refused++ }
        t.coordinator.disconnect()
        t.connectReady(watchA)
        guard.runApi(old, t.api) { api, _ -> api.setSettings(Settings()) }
        assertEquals(1, refused)
        assertTrue(t.settingsDelivered.isEmpty())
    }

    @Test fun `presence request for B preserves A and normalized A reuses session`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        assertFalse(t.coordinator.connect(watchB))
        assertTrue(t.coordinator.connect(watchA.lowercase().replace(':', '-')))
        assertEquals(ready, t.session.ready)
        assertEquals(listOf(watchA), t.attempts)
        assertEquals(0, t.closes)
    }
}
