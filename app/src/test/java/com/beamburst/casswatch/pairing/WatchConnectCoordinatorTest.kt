package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.data.local.reconcileConnectedSettings
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext
import org.avmedia.gshockapi.Alarm
import org.avmedia.gshockapi.Event
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.RepeatPeriod
import org.avmedia.gshockapi.Settings
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.io.TimeAdjustmentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchConnectCoordinatorTest {
    private val watchA = "AA:BB:CC:DD:EE:01"
    private val watchB = "AA:BB:CC:DD:EE:02"

    private fun scenario(block: suspend TestScope.() -> Unit) =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try { block() } finally { Dispatchers.resetMain() }
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
        var responseDelayMs = 0L
        var adjustmentDelayMs = 0L
        var opaqueDelayMs = 0L
        val attemptStarted = CompletableDeferred<Unit>()
        val writeQueued = CompletableDeferred<Unit>()
        private val starts = Channel<String>(Channel.UNLIMITED)
        private val pendingStarts = Channel<String>(Channel.UNLIMITED)
        val publicReads = mutableListOf<String>()
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
                        attemptStarted.complete(Unit)
                        starts.send(address)
                        // This job deliberately survives cancellation of waitForConnection.
                        scope.launch { gate.await(); pending = address; pendingStarts.send(address) }
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
                "getSettings", "getBasicSettings" -> {
                    val read: suspend () -> Settings = {
                        readStarted.complete(Unit)
                        delay(responseDelayMs)
                        try { response.await() } finally { readCancelled = !response.isCompleted }
                    }
                    read.startCoroutineUninterceptedOrReturn(args!![0] as Continuation<Settings>)
                }
                "getTimeAdjustment" -> {
                    val read: suspend () -> TimeAdjustmentInfo = {
                        delay(adjustmentDelayMs)
                        TimeAdjustmentInfo(true, 42)
                    }
                    read.startCoroutineUninterceptedOrReturn(args!![0] as Continuation<TimeAdjustmentInfo>)
                }
                "getDSTWatchState", "getDSTForWorldCities", "getWorldCities" -> {
                    val read: suspend () -> String = {
                        publicReads += method.name
                        delay(responseDelayMs)
                        "cached"
                    }
                    read.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<String>)
                }
                "getScratchpadData" -> {
                    publicReads += method.name
                    byteArrayOf(0, 0, 0)
                }
                "getAlarms" -> {
                    val read: suspend () -> ArrayList<Alarm> = { delay(opaqueDelayMs); arrayListOf() }
                    read.startCoroutineUninterceptedOrReturn(args!![0] as Continuation<ArrayList<Alarm>>)
                }
                "getEventFromWatch" -> {
                    val read: suspend () -> Event = { delay(opaqueDelayMs); Event("", null, null, RepeatPeriod.NEVER, null, false, false) }
                    read.startCoroutineUninterceptedOrReturn(args!![1] as Continuation<Event>)
                }
                "setTime", "primeTimeCache" -> {
                    val call: suspend () -> Unit = { delay(opaqueDelayMs) }
                    call.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Unit>)
                }
                "setSettings" -> {
                    val value = args!![0] as Settings
                    val gate = writeGate
                    scope.launch { gate.await(); settingsDelivered += session.address to value }
                    writeQueued.complete(Unit)
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
            delay(opaqueDelayMs)
            session.ready?.let { initializationEvents += it.generation }
        }

        suspend fun awaitAttempt(address: String) {
            assertEquals(address, starts.receive())
            assertEquals(address, pendingStarts.receive())
        }

        suspend fun setup(address: String, device: Any): WatchConnectionSession.Ready? = scope.async {
            runCatching {
                val ready = handler.setup(address, device)
                if (ready == null) coordinator.closeRejectedSetup()
                else if (handler.needsInitialization) coordinator.initializeReconnect(ready)
                ready
            }
        }.await().getOrThrow()

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

    @Test fun `screen reads wait for actual initialization rather than setup`() = scenario {
        val t = Transport(this)
        val selected = { watchA }
        val guard = WatchWriteGuard(selected, t.session, t.coordinator) {}
        val connection = async { t.coordinator.connect(watchA) }
        t.awaitAttempt(watchA)
        t.connected = true
        val ready = t.setup(watchA, Any())!!
        assertNull(guard.capture())
        var screenRead = false
        val read = async { guard.readApi(ready, t.api) { watch, _ ->
            watch.getScratchpadData(0, 3)
            screenRead = true
        } }
        runCurrent()
        assertFalse(screenRead)
        assertFalse(t.publicReads.contains("getScratchpadData"))
        t.initialization.complete(Unit)
        assertTrue(connection.await())
        read.await()
        assertTrue(screenRead)
        assertEquals(ready, guard.capture())
    }

    @Test fun `auto reconnect initialization timeout ends generation and next connect succeeds`() = scenario {
        val t = Transport(this)
        val device = Any()
        t.connectReady(watchA, device)
        t.connected = false
        t.disconnectEvent(device)
        t.connected = true
        t.opaqueDelayMs = WATCH_INITIALIZATION_TIMEOUT_MS + 1
        assertTrue(runCatching { t.setup(watchA, device) }.exceptionOrNull() is WatchResponseTimeoutException)
        assertEquals(WatchConnectionSession.Disconnected, t.session.state.value)
        assertNull(t.session.initialized.value)
        assertEquals(1, t.closes)
        assertEquals(2, t.uiDisconnects)
        t.opaqueDelayMs = 0
        assertTrue(t.session.isInitialized(t.connectReady(watchA)))
    }

    @Test fun `stale initialization completion cannot initialize replacement generation`() = scenario {
        val t = Transport(this)
        val old = t.connectReady(watchA)
        val replacement = t.session.renewed(old)!!
        org.avmedia.gshockapi.ProgressEvents.onNext("WatchInitializationCompleted")
        t.session.initializationCompleted(old)
        runCurrent()
        assertFalse(t.session.isInitialized(replacement))
        assertNull(t.session.initialized.value)
    }

    @Test fun `screen waiting for initialization returns when generation ends`() = scenario {
        val t = Transport(this)
        t.session.begin(t.session.state.value, watchA)
        val ready = t.session.established(watchA)!!
        t.connected = true
        val guard = WatchWriteGuard({ watchA }, t.session, t.coordinator) {}
        var readCalled = false
        val read = async { guard.readApi(ready, t.api) { _, _ -> readCalled = true } }
        runCurrent()
        assertFalse(read.isCompleted)
        t.coordinator.disconnect()
        read.await()
        assertFalse(readCalled)
    }

    @Test fun `screen initialization gate expires within initialization budget`() = scenario {
        val t = Transport(this)
        t.session.begin(t.session.state.value, watchA)
        val ready = t.session.established(watchA)!!
        t.connected = true
        val guard = WatchWriteGuard({ watchA }, t.session, t.coordinator) {}
        var readCalled = false
        val read = async { guard.readApi(ready, t.api) { _, _ -> readCalled = true } }
        advanceTimeBy(WATCH_INITIALIZATION_TIMEOUT_MS)
        runCurrent()
        assertTrue(read.isCompleted)
        read.await()
        assertFalse(readCalled)
    }

    @Test fun `CONNECTED before identity retry awaits readiness`() = scenario {
        val t = Transport(this)
        t.session.begin(t.session.state.value, watchA)
        t.connected = true
        val started = CompletableDeferred<Unit>()
        val retry = async { started.complete(Unit); t.coordinator.connect(watchA) }
        started.await()
        runCurrent()
        assertFalse(retry.isCompleted)
        val ready = t.setup(watchA, Any())!!
        t.session.initializationCompleted(ready)
        assertTrue(retry.await())
        assertTrue(t.attempts.isEmpty())
        assertEquals(0, t.closes)
    }

    @Test fun `setup racing connectivity status is retained`() = scenario {
        val t = Transport(this)
        t.session.begin(t.session.state.value, watchA)
        t.onStatus = {
            t.connected = true
            t.handler.setup(watchA, Any())?.let(t.session::initializationCompleted)
        }
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
                accepted?.let(t.session::initializationCompleted)
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
        val work = async {
            runCatching {
                t.coordinator.withSession(ready) { check -> t.api.guardWrites(check, responseTimeoutMs = 100).getSettings() }
            }
        }
        t.readStarted.await()
        advanceTimeBy(100)
        runCurrent()
        val failed = work.await()
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
        val queuedStarted = CompletableDeferred<Unit>()
        val queued = async {
            queuedStarted.complete(Unit)
            runCatching { t.coordinator.withSession(ready) { ran = true } }
        }
        queuedStarted.await()
        runCurrent()
        assertFalse(queued.isCompleted)
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

    @Test fun `observed disconnected transport permits same device reconnect without raw down event`() = scenario {
        val t = Transport(this)
        val device = Any()
        val old = t.connectReady(watchA, device)
        val work = t.stuckRead(old)
        t.connected = false
        t.handler.observeTransport(t.connected, old)
        t.connected = true
        val ready = t.setup(watchA, device)!!
        assertTrue(work.await().isFailure)
        assertTrue(ready.generation > old.generation)
        assertEquals(2, t.initCalls)
        assertEquals(1, t.uiDisconnects)
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
        val attempt = async { t.coordinator.connect(watchA, timeoutMs = 1_000) }
        t.attemptStarted.await()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(attempt.await())
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
        val work = async {
            runCatching {
                t.coordinator.withSession(ready) { check ->
                    reconcileConnectedSettings(t.api, check, Settings(), dirty,
                        save = { error("Unexpected read") }, clearDirty = { dirty = false })
                }
            }
        }
        t.writeQueued.await()
        runCurrent()
        assertTrue(work.await().isFailure)
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

    @Test fun `settings responses each get a full timeout despite ten seconds total`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        t.response.complete(Settings())
        t.responseDelayMs = 5_000
        t.adjustmentDelayMs = 5_000
        val work = async { t.coordinator.withSession(ready) { check -> t.api.guardWrites(check).getSettings() } }
        advanceUntilIdle()
        assertTrue(work.await().timeAdjustment)
        assertEquals(42, work.await().adjustmentTimeMinutes)
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test fun `slow second settings response times out individually and leaves transport connected`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        t.response.complete(Settings())
        t.responseDelayMs = 5_000
        t.adjustmentDelayMs = 9_000
        val work = async {
            runCatching { t.coordinator.withSession(ready) { check -> t.api.guardWrites(check).getSettings() } }
        }
        advanceUntilIdle()
        assertTrue(work.await().exceptionOrNull() is WatchResponseTimeoutException)
        assertEquals(13_000L, testScheduler.currentTime)
        assertTrue(t.connected)
        assertEquals(ready, t.session.ready)
        assertEquals(0, t.closes)
    }

    @Test fun `opaque initialization and alarms allow two response budgets`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        t.opaqueDelayMs = 10_000
        val work = async {
            t.coordinator.withSession(ready) { check ->
                val api = t.api.guardWrites(check)
                assertTrue(api.init())
                api.getAlarms()
            }
        }
        advanceUntilIdle()
        assertTrue(work.await().isEmpty())
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test fun `opaque alarm timeout releases work without ending session`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        t.opaqueDelayMs = WATCH_ALARMS_TIMEOUT_MS + 1
        val work = async {
            runCatching { t.coordinator.withSession(ready) { check -> t.api.guardWrites(check).getAlarms() } }
        }
        advanceTimeBy(WATCH_ALARMS_TIMEOUT_MS)
        runCurrent()
        assertTrue(work.await().exceptionOrNull() is WatchResponseTimeoutException)
        assertEquals(ready, t.session.ready)
    }

    @Test fun `each of five reminders gets its own two response budget`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        t.opaqueDelayMs = 10_000
        val work = async { t.coordinator.withSession(ready) { check -> t.api.guardWrites(check).getEventsFromWatch() } }
        advanceUntilIdle()
        assertEquals(5, work.await().size)
        assertEquals(50_000L, testScheduler.currentTime)
    }

    @Test fun `opaque set time allows all fourteen internal responses`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        t.opaqueDelayMs = 14 * 7_000L
        val work = async {
            t.coordinator.withSession(ready) { check -> t.api.guardWrites(check).setTime("Europe/Warsaw", 0L) }
        }
        advanceUntilIdle()
        work.await()
        assertEquals(98_000L, testScheduler.currentTime)
        t.opaqueDelayMs = WATCH_TIME_TIMEOUT_MS + 1
        val timeout = async {
            runCatching { t.coordinator.withSession(ready) { check -> t.api.guardWrites(check).setTime("Europe/Warsaw", 0L) } }
        }
        advanceUntilIdle()
        assertTrue(timeout.await().exceptionOrNull() is WatchResponseTimeoutException)
    }

    @Test fun `time priming bounds each exposed read independently`() = scenario {
        val t = Transport(this)
        val ready = t.connectReady(watchA)
        val oldDstCount = WatchInfo.dstCount
        val oldCitiesCount = WatchInfo.worldCitiesCount
        val oldHasCities = WatchInfo.hasWorldCities
        try {
            WatchInfo.dstCount = 3
            WatchInfo.worldCitiesCount = 6
            WatchInfo.hasWorldCities = true
            t.responseDelayMs = 5_000
            val work = async { t.coordinator.withSession(ready) { check -> t.api.guardWrites(check).primeTimeCache() } }
            advanceUntilIdle()
            work.await()
            assertEquals(14, t.publicReads.size)
            assertEquals(70_000L, testScheduler.currentTime)
        } finally {
            WatchInfo.dstCount = oldDstCount
            WatchInfo.worldCitiesCount = oldCitiesCount
            WatchInfo.hasWorldCities = oldHasCities
        }
    }

    @Test fun `multithreaded coordinator disconnect cancels active and queued work without waiting`() = scenario {
        withContext(Dispatchers.Default) {
            val connected = AtomicBoolean(true)
            val closes = AtomicInteger()
            val session = WatchConnectionSession()
            val api = Proxy.newProxyInstance(
                IGShockAPI::class.java.classLoader,
                arrayOf(IGShockAPI::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "isConnected" -> connected.get()
                    "disconnect" -> { connected.set(false); closes.incrementAndGet(); Unit }
                    else -> error("Unexpected API call: ${method.name}")
                }
            } as IGShockAPI
            val coordinator = WatchConnectCoordinator(api, session)
            session.begin(session.state.value, watchA)
            val ready = coordinator.connectionEvents.setup(watchA, Any())!!
            val activeStarted = CompletableDeferred<Unit>()
            val queuedStarted = CompletableDeferred<Unit>()
            val queuedRan = AtomicBoolean()
            coroutineScope {
                val active = async {
                    runCatching {
                        coordinator.withSession(ready) {
                            activeStarted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                }
                activeStarted.await()
                val queued = async {
                    queuedStarted.complete(Unit)
                    runCatching { coordinator.withSession(ready) { queuedRan.set(true) } }
                }
                queuedStarted.await()
                coordinator.disconnect()
                assertTrue(active.await().isFailure)
                assertTrue(queued.await().isFailure)
                assertFalse(queuedRan.get())
                assertEquals(1, closes.get())
                assertNull(session.ready)
            }
        }
    }

    @Test fun `multithreaded setup and delayed down race end old generation exactly once`() = scenario {
        withContext(Dispatchers.Default) {
            repeat(100) {
                val session = WatchConnectionSession()
                val events = WatchConnectionEvents(session)
                val device = Any()
                session.begin(session.state.value, watchA)
                val old = events.setup(watchA, device)!!
                val ends = AtomicInteger()
                session.onEnded { ends.incrementAndGet() }
                val start = CompletableDeferred<Unit>()
                val setupStarted = CompletableDeferred<Unit>()
                val downStarted = CompletableDeferred<Unit>()
                coroutineScope {
                    val setup = async(Dispatchers.Default) {
                        setupStarted.complete(Unit)
                        start.await()
                        events.setup(watchA, device)!!
                    }
                    val down = async(Dispatchers.Default) {
                        downStarted.complete(Unit)
                        start.await()
                        events.disconnect(old, transportConnected = true)
                    }
                    setupStarted.await()
                    downStarted.await()
                    start.complete(Unit)
                    val ready = setup.await()
                    down.await()
                    assertTrue(ready.generation > old.generation)
                    assertEquals(ready, session.ready)
                    assertEquals(1, ends.get())
                    assertTrue(events.needsInitialization)
                }
            }
        }
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
