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
        override suspend fun addresses() = states.keys.toList()
        override suspend fun load(address: String) = states[address] ?: AlarmState()
        override suspend fun save(address: String, state: AlarmState) { states[address] = state }
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
}
