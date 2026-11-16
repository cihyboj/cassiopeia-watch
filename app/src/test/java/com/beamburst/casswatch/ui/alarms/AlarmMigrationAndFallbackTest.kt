package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.SyncEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AlarmMigrationAndFallbackTest {
    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"
    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-09T06:58:00Z"), zone)
    private val alarm = AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake")
    private class Store(val states: MutableMap<String, AlarmState>) : AlarmPersistence {
        override suspend fun phoneAlarmBackupEnabled() = true
        var done = false
        var history = emptyList<SyncEntry>()
        override suspend fun addresses() = states.keys.toList()
        override suspend fun load(address: String) = states[address] ?: AlarmState()
        override suspend fun save(address: String, state: AlarmState) { states[address] = state }
        override suspend fun history(address: String) = history
        override suspend fun legacySlots() = setOf(9)
        override suspend fun migrationDone() = done
        override suspend fun finishMigration() { done = true }
    }
    private class Backend : AlarmScheduleBackend {
        val scheduled = mutableMapOf<AlarmKey, Long>()
        val expiry = mutableMapOf<AlarmKey, Long>()
        val legacyCancelled = mutableListOf<Int>()
        var failOnce = false
        override fun cancel(key: AlarmKey) { scheduled.remove(key); expiry.remove(key) }
        override fun cancelFallback(key: AlarmKey) { scheduled.remove(key) }
        override fun cancelLegacy(slot: Int) { legacyCancelled += slot }
        override fun expiry(key: AlarmKey, instant: Long?) { if (instant == null) expiry.remove(key) else expiry[key] = instant }
        override fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long): FallbackAvailability {
            if (failOnce) { failOnce = false; error("interrupted") }
            scheduled[key] = instant
            return FallbackAvailability.SCHEDULED
        }
    }
    @Test fun `identity migration retries cleanup after interruption and marks done after rebuild`() = runBlocking {
        val state = AlarmState(listOf(alarm), mapOf(0 to emptySet()))
        val store = Store(mutableMapOf(a to state, b to state))
        val backend = Backend().apply { failOnce = true }
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { zone }
        assertTrue(runCatching { coordinator.reconcileAll() }.isFailure)
        assertFalse(store.done)
        assertEquals(setOf(0, 1, 2, 3, 4, 9), backend.legacyCancelled.toSet())
        coordinator.reconcileAll()
        assertTrue(store.done)
        assertEquals(2, backend.legacyCancelled.count { it == 9 })
        assertEquals(setOf(AlarmKey(a, 0), AlarmKey(b, 0)), backend.scheduled.keys)
        coordinator.reconcileAll()
        assertEquals(2, backend.legacyCancelled.count { it == 9 })
        coordinator.cancelWatch(a)
        assertFalse(backend.scheduled.containsKey(AlarmKey(a, 0)))
        assertTrue(backend.scheduled.containsKey(AlarmKey(b, 0)))
    }
    @Test fun `cleanup discovers every persisted slot including orphaned sparse selections`() {
        val entries = JSONObject().apply {
            put("AlarmDaySelections_$b", """{"17":[]}""")
            put("PhoneAlarmDrafts_$a", """[{"hour":7,"minute":0,"enabled":true,"hasHourlyChime":false}]""")
        }
        assertEquals(setOf(0, 17), persistedAlarmSlots(entries))
    }
    @Test fun `recurring callback hands off once and schedules following occurrence`() = runBlocking {
        val store = Store(mutableMapOf(a to AlarmState(listOf(alarm)))).apply { done = true }
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { zone }
        coordinator.reconcileAll()
        val first = backend.scheduled.getValue(AlarmKey(a, 0))
        var handoffs = 0
        assertTrue(coordinator.fallback(AlarmKey(a, 0), first, "07:00:true") { handoffs++ })
        assertEquals(first + 86_400_000, backend.scheduled[AlarmKey(a, 0)])
        assertFalse(coordinator.fallback(AlarmKey(a, 0), first, "07:00:true") { handoffs++ })
        assertEquals(1, handoffs)
    }
    @Test fun `one time handoff keeps due time expiry scheduled`() = runBlocking {
        val store = Store(mutableMapOf(a to AlarmState(listOf(alarm.copy(oneShotLocalDate = "2026-10-09")), mapOf(0 to emptySet())))).apply { done = true }
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { zone }
        coordinator.reconcileAll()
        val first = backend.scheduled.getValue(AlarmKey(a, 0))
        coordinator.fallback(AlarmKey(a, 0), first, "07:00:true") {}
        assertFalse(backend.scheduled.containsKey(AlarmKey(a, 0)))
        assertEquals(first, backend.expiry[AlarmKey(a, 0)])
        assertNull(store.states[a]!!.alarms[0].firedAt)
    }
}
