package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.SyncEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AlarmCoordinatorTest {
    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"
    private val clock = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("UTC"))
    private val initial = AlarmState(listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, "", oneShotLocalDate = "2026-10-09")), mapOf(0 to emptySet()))
    private class Store(val states: MutableMap<String, AlarmState>) : AlarmPersistence {
        var initialization: suspend () -> Unit = {}
        var beforeSave: suspend () -> Unit = {}
        override suspend fun initialize() = initialization()
        override suspend fun addresses() = states.keys.toList()
        override suspend fun load(address: String) = states[address] ?: AlarmState()
        override suspend fun save(address: String, state: AlarmState) { beforeSave(); states[address] = state }
        override suspend fun history(address: String): List<SyncEntry> = emptyList()
        override suspend fun legacySlots(): Set<Int> = emptySet()
        override suspend fun migrationDone() = true
        override suspend fun finishMigration() = Unit
    }
    private class Backend : AlarmScheduleBackend {
        val scheduled = mutableMapOf<AlarmKey, Long>()
        override fun cancel(key: AlarmKey) { scheduled.remove(key) }
        override fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long): FallbackAvailability {
            scheduled[key] = instant
            return FallbackAvailability.SCHEDULED
        }
        override fun cancelFallback(key: AlarmKey) = cancel(key)
        override fun cancelLegacy(slot: Int) = Unit
        override fun expiry(key: AlarmKey, instant: Long?) = Unit
    }
    @Test fun `reconcile after process death consumes elapsed persisted occurrence without screen`() = runBlocking {
        val store = Store(mutableMapOf(a to initial))
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), clock) { ZoneId.of("UTC") }
        coordinator.reconcile(a)
        assertNotNull(store.states[a]!!.alarms[0].firedAt)
        assertTrue(store.states[a]!!.dirty)
    }
    @Test fun `serialized edit finishes before reconcile reads`() = runBlocking {
        val store = Store(mutableMapOf(a to initial))
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), clock) { ZoneId.of("UTC") }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val edit = async {
            coordinator.serialized {
                started.complete(Unit)
                release.await()
                store.save(a, initial.copy(alarms = listOf(initial.alarms[0].copy(oneShotLocalDate = "2026-10-10"))))
            }
        }
        started.await()
        val reconcile = async { coordinator.reconcile(a) }
        release.complete(Unit)
        edit.await(); reconcile.await()
        assertNull(store.states[a]!!.alarms[0].firedAt)
    }
    @Test fun `serialized removal prevents reconcile from rebuilding a forgotten watch`() = runBlocking {
        val future = initial.copy(alarms = listOf(initial.alarms[0].copy(oneShotLocalDate = "2026-10-10")))
        val store = Store(mutableMapOf(a to future, b to future))
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { ZoneId.of("UTC") }
        coordinator.reconcileAll()
        coordinator.forgetWatch(a) { store.states.remove(a) }
        coordinator.reconcile(a)
        assertFalse(backend.scheduled.containsKey(AlarmKey(a, 0)))
        assertTrue(backend.scheduled.containsKey(AlarmKey(b, 0)))
    }
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }
    @Test fun `late daily callback schedules tomorrow even while process survives`() = runBlocking {
        val store = Store(mutableMapOf(a to AlarmState(listOf(initial.alarms[0].copy(oneShotLocalDate = null)))))
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { ZoneId.of("UTC") }
        val due = Instant.parse("2026-10-09T07:00:00Z").toEpochMilli()
        assertFalse(coordinator.fallback(AlarmKey(a, 0), due, "07:00:true") { fail("Late launch") })
        assertEquals(due + 86_400_000, backend.scheduled[AlarmKey(a, 0)])
    }
    @Test fun `late weekly callback includes next week boundary`() = runBlocking {
        val store = Store(mutableMapOf(a to AlarmState(listOf(initial.alarms[0].copy(oneShotLocalDate = null)), mapOf(0 to setOf(java.time.DayOfWeek.FRIDAY)))))
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { ZoneId.of("UTC") }
        val due = Instant.parse("2026-10-09T07:00:00Z").toEpochMilli()
        assertFalse(coordinator.fallback(AlarmKey(a, 0), due, "07:00:true") { fail("Late launch") })
        assertEquals(due + 7 * 86_400_000, backend.scheduled[AlarmKey(a, 0)])
    }
    @Test fun `deadline crossed during awaited persistence skips launch and rebuilds tomorrow`() = runBlocking {
        val moving = MovingClock(Instant.parse("2026-10-09T06:58:00Z"))
        val store = Store(mutableMapOf(a to AlarmState(listOf(initial.alarms[0].copy(oneShotLocalDate = null)))))
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, moving) { ZoneId.of("UTC") }
        store.beforeSave = { moving.now = Instant.parse("2026-10-09T07:00:00Z") }
        val due = moving.now.plusSeconds(120).toEpochMilli()
        assertFalse(coordinator.fallback(AlarmKey(a, 0), due, "07:00:true") { fail("Expired during storage") })
        assertNull(store.states[a]!!.alarms[0].fallbackStatus)
        assertEquals(AlarmFallbackOutcome(0, "failed", due), store.states[a]!!.fallbackHistory.single())
        assertEquals(due, store.states[a]!!.alarms[0].fallbackAttemptAt)
        assertEquals(due + 86_400_000, backend.scheduled[AlarmKey(a, 0)])
    }
    @Test fun `failed handoff persists failure without success receipt and keeps next daily fallback`() = runBlocking {
        val moving = MovingClock(Instant.parse("2026-10-09T06:58:00Z"))
        val store = Store(mutableMapOf(a to AlarmState(listOf(initial.alarms[0].copy(oneShotLocalDate = null)))))
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, moving) { ZoneId.of("UTC") }
        val due = moving.now.plusSeconds(120).toEpochMilli()
        assertTrue(runCatching { coordinator.fallback(AlarmKey(a, 0), due, "07:00:true") { error("No clock") } }.isFailure)
        assertNull(store.states[a]!!.alarms[0].fallbackAttemptAt)
        assertEquals("failed", store.states[a]!!.alarms[0].fallbackStatus)
        assertEquals(due + 86_400_000, backend.scheduled[AlarmKey(a, 0)])
    }
    @Test fun `successful handoff has no receipt before launch and records requested afterward`() = runBlocking {
        val moving = MovingClock(Instant.parse("2026-10-09T06:58:00Z"))
        val store = Store(mutableMapOf(a to AlarmState(listOf(initial.alarms[0].copy(oneShotLocalDate = null)))))
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), moving) { ZoneId.of("UTC") }
        val due = moving.now.plusSeconds(120).toEpochMilli()
        assertTrue(coordinator.fallback(AlarmKey(a, 0), due, "07:00:true") {
            assertEquals(due, store.states[a]!!.alarms[0].fallbackAttemptAt)
            assertEquals("pending", store.states[a]!!.alarms[0].fallbackStatus)
        })
        assertEquals(due, store.states[a]!!.alarms[0].fallbackAttemptAt)
        assertEquals("requested", store.states[a]!!.alarms[0].fallbackStatus)
    }
    @Test fun `all coordinator callers wait for legacy initialization before editing`() = runBlocking {
        val store = Store(mutableMapOf(a to AlarmState(listOf(initial.alarms[0].copy(oneShotLocalDate = null)))))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var initializations = 0
        store.initialization = {
            initializations++
            entered.complete(Unit)
            release.await()
            store.states[a] = store.states[a]!!.copy(days = mapOf(0 to emptySet()))
        }
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), clock) { ZoneId.of("UTC") }
        val edit = async { coordinator.update(a) { state ->
            assertEquals(emptySet<java.time.DayOfWeek>(), state.days[0])
            state.copy(dirty = true)
        } }
        entered.await()
        val other = async { coordinator.snapshot(a) }
        kotlinx.coroutines.yield()
        assertFalse(other.isCompleted)
        release.complete(Unit)
        edit.await(); other.await()
        coordinator.cancelWatch(a)
        assertEquals(1, initializations)
    }
    @Test fun `receiver budget cancels slow persistence without handoff then permits recovery`() = runBlocking {
        val moving = MovingClock(Instant.parse("2026-10-09T06:58:00Z"))
        val store = Store(mutableMapOf(a to AlarmState(listOf(initial.alarms[0].copy(oneShotLocalDate = null)))))
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), moving) { ZoneId.of("UTC") }
        store.beforeSave = { kotlinx.coroutines.awaitCancellation() }
        assertNull(kotlinx.coroutines.withTimeoutOrNull(50) {
            coordinator.fallback(AlarmKey(a, 0), moving.now.plusSeconds(120).toEpochMilli(), "07:00:true") { fail("Timed out") }
        })
        store.beforeSave = {}
        assertNotNull(coordinator.snapshot(a))
        assertTrue(ALARM_RECEIVER_TIMEOUT_MS < 10_000)
    }
    @Test fun `replacement awaits imported legacy migration before creating alarm state`() = runBlocking {
        val store = Store(mutableMapOf(b to AlarmState()))
        var imported = false
        store.initialization = {
            if (imported) store.states[a] = store.states[a]!!.copy(days = mapOf(0 to emptySet()))
        }
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), clock) { ZoneId.of("UTC") }
        coordinator.replaceAll {
            store.states.clear()
            store.states[a] = AlarmState(listOf(initial.alarms[0].copy(oneShotLocalDate = null)))
            imported = true
        }
        val restored = coordinator.snapshot(a)
        assertEquals(emptySet<java.time.DayOfWeek>(), restored.days[0])
        assertEquals("2026-10-10", restored.alarms[0].oneShotLocalDate)
    }
    @Test fun `pending receipt recovered after interrupted handoff is never replayed`() = runBlocking {
        val moving = MovingClock(Instant.parse("2026-10-09T06:58:00Z"))
        val due = moving.millis() + 120_000
        val alarm = initial.alarms[0].copy(oneShotLocalDate = null, fallbackStatus = "pending", fallbackStatusAt = due)
        val store = Store(mutableMapOf(a to AlarmState(listOf(alarm))))
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, moving) { ZoneId.of("UTC") }
        coordinator.reconcile(a)
        assertEquals(due + 86_400_000, backend.scheduled[AlarmKey(a, 0)])
        assertFalse(coordinator.fallback(AlarmKey(a, 0), due, "07:00:true") { fail("Replayed uncertain attempt") })
        assertEquals("pending", activeFallbackStatus(store.states[a]!!.alarms[0], moving.millis()))
    }
    @Test fun `requested persistence interruption retains pending attempt and cannot hand off twice`() = runBlocking {
        val moving = MovingClock(Instant.parse("2026-10-09T06:58:00Z"))
        val due = moving.millis() + 120_000
        val store = Store(mutableMapOf(a to AlarmState(listOf(initial.alarms[0].copy(oneShotLocalDate = null)))))
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), moving) { ZoneId.of("UTC") }
        var launches = 0
        store.beforeSave = {
            if (store.states[a]!!.alarms[0].fallbackStatus == "pending") throw kotlinx.coroutines.CancellationException("Killed after launch")
        }
        assertTrue(runCatching { coordinator.fallback(AlarmKey(a, 0), due, "07:00:true") { launches++ } }.isFailure)
        store.beforeSave = {}
        assertEquals("pending", store.states[a]!!.alarms[0].fallbackStatus)
        assertFalse(coordinator.fallback(AlarmKey(a, 0), due, "07:00:true") { launches++ })
        assertEquals(1, launches)
    }
    @Test fun `past occurrence warnings expire into separate history during reconciliation`() = runBlocking {
        val due = clock.millis() - 3_600_000
        val old = initial.alarms[0].copy(oneShotLocalDate = null, fallbackStatus = "failed", fallbackStatusAt = due)
        assertNull(activeFallbackStatus(old, clock.millis()))
        val store = Store(mutableMapOf(a to AlarmState(listOf(old))))
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), clock) { ZoneId.of("UTC") }
        coordinator.reconcile(a)
        assertNull(store.states[a]!!.alarms[0].fallbackStatus)
        assertEquals(AlarmFallbackOutcome(0, "failed", due), store.states[a]!!.fallbackHistory.single())
        assertEquals(due, store.states[a]!!.alarms[0].fallbackAttemptAt)
    }

}
