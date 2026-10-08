package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.pairing.WatchConnectionChangedException
import kotlinx.coroutines.runBlocking
import org.avmedia.gshockapi.Alarm
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AlarmPayloadWriterTest {
    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"
    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-09T06:58:00Z"), zone)
    private class Store(val states: MutableMap<String, AlarmState>) : AlarmPersistence {
        var beforeSave: () -> Unit = {}
        override suspend fun addresses() = states.keys.toList()
        override suspend fun load(address: String) = states.getValue(address)
        override suspend fun save(address: String, state: AlarmState) { beforeSave(); states[address] = state }
        override suspend fun history(address: String): List<SyncEntry> = emptyList()
        override suspend fun legacySlots(): Set<Int> = emptySet()
        override suspend fun migrationDone() = true
        override suspend fun finishMigration() = Unit
    }
    private class Backend : AlarmScheduleBackend {
        override fun cancel(key: AlarmKey) = Unit
        override fun cancelFallback(key: AlarmKey) = Unit
        override fun cancelLegacy(slot: Int) = Unit
        override fun expiry(key: AlarmKey, instant: Long?) = Unit
        override fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long) = FallbackAvailability.SCHEDULED
    }
    private fun scenario(timeClock: Clock = clock, block: suspend (AlarmPayloadWriter, Store, WatchConnectionSession, WatchConnectionSession.Ready, MutableList<AlarmPayloadRecord>) -> Unit) = runBlocking {
        val alarms = listOf(Alarm(7, 0, true, false, "Wake"))
        val previous = watchPayloadsAfterWrite(a, alarms, clock, zone)
        val store = Store(mutableMapOf(a to AlarmState(watchPayloads = previous), b to AlarmState()))
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), timeClock) { zone }
        val session = WatchConnectionSession()
        session.begin(session.state.value, a)
        val ready = session.established(a)!!
        val records = mutableListOf<AlarmPayloadRecord>()
        val writer = AlarmPayloadWriter(coordinator, AlarmSchedulingTime(timeClock) { zone }, session) { records += it }
        block(writer, store, session, ready, records)
    }
    @Test fun `actual successful payload is recorded under captured connection address and exact slots`() = scenario { writer, store, _, ready, records ->
        val payload = listOf(Alarm(7, 0, false, true, "Consumed"), Alarm(8, 0, true, false, "Daily"))
        writer.write(ready, payload, AlarmState(), SyncTrigger.MANUAL_SEND_ALARMS, true, {}) {
            assertTrue(store.states[a]!!.watchPayloads.isEmpty())
        }
        assertEquals(a, records.single().address)
        assertEquals(payload, records.single().payload)
        assertFalse(store.states[a]!!.watchPayloads.getValue(0).enabled)
        assertEquals(8, store.states[a]!!.watchPayloads.getValue(1).hour)
        assertTrue(store.states[b]!!.watchPayloads.isEmpty())
    }
    @Test fun `failed alarm write invalidates previously confirmed enabled payload`() = scenario { writer, store, _, ready, records ->
        assertTrue(runCatching {
            writer.write(ready, listOf(Alarm(7, 0, false, false, "")), AlarmState(), SyncTrigger.MANUAL_SEND_ALARMS, false, {}) { error("Transport failure") }
        }.isFailure)
        assertTrue(store.states[a]!!.watchPayloads.isEmpty())
        assertFalse(records.single().success)
    }
    @Test fun `changed session after send cannot confirm or record under replacement watch`() = scenario { writer, store, session, ready, records ->
        val confirm: suspend () -> Unit = { if (!session.isCurrent(ready)) throw WatchConnectionChangedException() }
        val result = runCatching {
            writer.write(ready, listOf(Alarm(7, 0, true, false, "")), AlarmState(), SyncTrigger.MANUAL_SEND_ALARMS, false, confirm) {
                session.ended(ready)
                session.begin(session.state.value, b)
                session.established(b)
            }
        }
        assertTrue(result.exceptionOrNull() is WatchConnectionChangedException)
        assertTrue(records.isEmpty())
        assertTrue(store.states[a]!!.watchPayloads.isEmpty())
        assertTrue(store.states[b]!!.watchPayloads.isEmpty())
    }
    @Test fun `consumed snapshot shared payload does not reenable elapsed one time alarm`() {
        val old = AlarmState(listOf(AlarmSyncStorage.StoredAlarm(6, 0, true, false, "Wake", oneShotLocalDate = "2026-10-09")), mapOf(0 to emptySet()))
        val snapshot = reconcileAlarmState(old, clock, zone)
        val payload = hourlySignalAlarmPayload(snapshot.watchAlarms(), true, snapshot.firedAts().keys, snapshot.oneShotOccurrences(), clock, zone)
        assertFalse(payload.single().enabled)
        assertTrue(payload.single().hasHourlyChime)
    }
    @Test fun `shared writer disables one time alarm elapsed during awaited invalidation`() {
        var instant = clock.instant()
        val moving = object : Clock() {
            override fun getZone() = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId) = this
            override fun instant() = instant
        }
        scenario(moving) { writer, store, _, ready, records ->
            val snapshot = AlarmState(listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake",
                oneShotLocalDate = "2026-10-09")), mapOf(0 to emptySet()))
            store.states[a] = snapshot
            store.beforeSave = { instant = Instant.parse("2026-10-09T07:01:00Z") }
            writer.write(ready, snapshot.watchAlarms(), snapshot, SyncTrigger.MANUAL_SEND_ALARMS, false, {}) { actual ->
                assertFalse(actual.single().enabled)
            }
            assertFalse(records.single().payload.single().enabled)
            assertFalse(store.states[a]!!.watchPayloads.getValue(0).enabled)
        }
    }
    @Test fun `session changed during confirmation persistence discards the committed evidence`() = scenario { writer, store, session, ready, records ->
        var saves = 0
        store.beforeSave = {
            saves++
            if (saves == 2) session.ended(ready)
        }
        val confirm: suspend () -> Unit = { if (!session.isCurrent(ready)) throw WatchConnectionChangedException() }
        val result = runCatching {
            writer.write(ready, listOf(Alarm(7, 0, true, false, "Wake")), AlarmState(), SyncTrigger.MANUAL_SEND_ALARMS, false, confirm) {}
        }
        assertTrue(result.exceptionOrNull() is WatchConnectionChangedException)
        assertTrue(store.states[a]!!.watchPayloads.isEmpty())
        assertTrue(records.isEmpty())
    }
}
