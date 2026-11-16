package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.receivers.isAlarmLifecycleAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AlarmLifecycleTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val utc = ZoneId.of("UTC")
    private val alarm = AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake", oneShotLocalDate = "2026-10-09")
    private val initial = AlarmState(listOf(alarm), mapOf(0 to emptySet()), true)
    private class Store(val states: MutableMap<String, AlarmState>) : AlarmPersistence {
        override suspend fun phoneAlarmBackupEnabled() = true
        var beforeSave: (suspend () -> Unit)? = null
        var loads = 0
        override suspend fun addresses() = states.keys.toList()
        override suspend fun load(address: String): AlarmState { loads++; return states[address] ?: AlarmState() }
        override suspend fun save(address: String, state: AlarmState) { beforeSave?.invoke(); states[address] = state }
        override suspend fun history(address: String): List<SyncEntry> = emptyList()
        override suspend fun legacySlots(): Set<Int> = emptySet()
        override suspend fun migrationDone() = true
        override suspend fun finishMigration() = Unit
    }
    private class Backend : AlarmScheduleBackend {
        val scheduled = mutableMapOf<AlarmKey, Long>()
        var available = true
        override fun cancel(key: AlarmKey) { scheduled.remove(key) }
        override fun cancelFallback(key: AlarmKey) = cancel(key)
        override fun cancelLegacy(slot: Int) = Unit
        override fun expiry(key: AlarmKey, instant: Long?) = Unit
        override fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long): FallbackAvailability {
            if (!available) return FallbackAvailability.UNAVAILABLE
            scheduled[key] = instant
            return FallbackAvailability.SCHEDULED
        }
    }
    @Test fun `update time zone and permission broadcasts are accepted`() {
        listOf("android.intent.action.MY_PACKAGE_REPLACED", "android.intent.action.TIME_SET",
            "android.intent.action.TIMEZONE_CHANGED", "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
            .forEach { assertTrue(isAlarmLifecycleAction(it)) }
        assertFalse(isAlarmLifecycleAction(null))
        assertFalse(isAlarmLifecycleAction("unknown"))
    }
    @Test fun `boot rebuild and zone change retain date and recompute instant`() = runBlocking {
        val store = Store(mutableMapOf(address to initial))
        val backend = Backend()
        val clock = Clock.fixed(Instant.parse("2026-10-08T23:00:00Z"), utc)
        var zone = utc
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { zone }
        coordinator.reconcileAll()
        assertEquals(Instant.parse("2026-10-09T07:00:00Z").toEpochMilli(), backend.scheduled[AlarmKey(address, 0)])
        zone = ZoneId.of("Europe/Warsaw")
        coordinator.reconcileAll()
        assertEquals(Instant.parse("2026-10-09T05:00:00Z").toEpochMilli(), backend.scheduled[AlarmKey(address, 0)])
        assertEquals("2026-10-09", store.states[address]!!.alarms[0].oneShotLocalDate)
    }
    @Test fun `permission grant rebuilds previously unavailable fallback`() = runBlocking {
        val store = Store(mutableMapOf(address to initial))
        val backend = Backend().apply { available = false }
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, Clock.fixed(Instant.parse("2026-10-08T23:00:00Z"), utc)) { utc }
        coordinator.reconcileAll()
        assertEquals(setOf(AlarmKey(address, 0)), coordinator.unavailable.value)
        backend.available = true
        coordinator.reconcileAll()
        assertTrue(coordinator.unavailable.value.isEmpty())
        assertTrue(backend.scheduled.containsKey(AlarmKey(address, 0)))
    }
    @Test fun `reconnect snapshot expires before payload construction without a screen`() = runBlocking {
        val store = Store(mutableMapOf(address to initial))
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), utc)) { utc }
        val snapshot = coordinator.snapshot(address)
        assertNotNull(snapshot.alarms[0].firedAt)
        val payload = AlarmSchedulePlanner.applyWeeklySchedule(snapshot.watchAlarms(), snapshot.days,
            java.time.LocalDateTime.of(2026, 10, 9, 8, 0), AlarmViewMode.WEEKLY, snapshot.firedAts(), snapshot.oneShotLocalDates())
        assertFalse(payload[0].enabled)
        assertEquals(snapshot, store.states[address])
    }
    @Test fun `actual edit cannot be overtaken by reconcile during its awaited write`() = runBlocking {
        val store = Store(mutableMapOf(address to initial))
        val clock = Clock.fixed(Instant.parse("2026-10-08T23:00:00Z"), utc)
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), clock) { utc }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeSave = { entered.complete(Unit); release.await() }
        val edit = async { coordinator.update(address) { state -> editAlarmState(state, 0, state.alarms[0].copy(hour = 8), emptySet(), false, clock, utc) } }
        entered.await()
        val loadsBeforeReconcile = store.loads
        val reconcile = async { coordinator.reconcileAll() }
        kotlinx.coroutines.yield()
        assertFalse(reconcile.isCompleted)
        assertEquals(loadsBeforeReconcile, store.loads)
        release.complete(Unit)
        edit.await(); reconcile.await()
        assertEquals(8, store.states[address]!!.alarms[0].hour)
        assertNull(store.states[address]!!.alarms[0].firedAt)
    }
    @Test fun `forget holds cancellation and removal together while reconcile waits`() = runBlocking {
        val store = Store(mutableMapOf(address to initial))
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, Clock.fixed(Instant.parse("2026-10-08T23:00:00Z"), utc)) { utc }
        coordinator.reconcileAll()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val forget = async { coordinator.forgetWatch(address) { entered.complete(Unit); release.await(); store.states.remove(address) } }
        entered.await()
        assertTrue(backend.scheduled.isEmpty())
        val reconcile = async { coordinator.reconcileAll() }
        kotlinx.coroutines.yield()
        assertFalse(reconcile.isCompleted)
        release.complete(Unit)
        forget.await(); reconcile.await()
        assertTrue(backend.scheduled.isEmpty())
        assertTrue(store.states.isEmpty())
    }
    @Test fun `hourly signal payload disables occurrence elapsed during the read`() {
        val clock = Clock.fixed(Instant.parse("2026-10-09T07:01:00Z"), utc)
        val payload = hourlySignalAlarmPayload(initial.watchAlarms(), true, emptySet(), initial.oneShotOccurrences(), clock, utc)
        assertFalse(payload[0].enabled)
        assertTrue(payload[0].hasHourlyChime)
    }
}
