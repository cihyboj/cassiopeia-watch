package com.beamburst.casswatch.ui.alarms

import CachedIO
import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchConnectionSession
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.avmedia.gshockapi.Alarm
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AlarmPayloadWriterTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-09T06:58:00Z"), zone)
    private val payload = listOf(Alarm(7, 0, true, false, "Wake"))
    private class Store(var state: AlarmState, val address: String) : AlarmPersistence {
        var beforeSave: (AlarmState) -> Unit = {}
        override suspend fun addresses() = listOf(address)
        override suspend fun load(address: String) = state
        override suspend fun save(address: String, state: AlarmState) { beforeSave(state); this.state = state }
        override suspend fun history(address: String): List<SyncEntry> = emptyList()
        override suspend fun legacySlots(): Set<Int> = emptySet()
        override suspend fun migrationDone() = true
        override suspend fun finishMigration() = Unit
    }
    private class Backend : AlarmScheduleBackend {
        val scheduled = mutableMapOf<AlarmKey, Long>()
        override fun cancel(key: AlarmKey) { scheduled.remove(key) }
        override fun cancelFallback(key: AlarmKey) = cancel(key)
        override fun cancelLegacy(slot: Int) = Unit
        override fun expiry(key: AlarmKey, instant: Long?) = Unit
        override fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long): FallbackAvailability {
            scheduled[key] = instant
            return FallbackAvailability.SCHEDULED
        }
    }
    private inner class Scenario {
        val previous = watchPayloadsAfterWrite(address, payload, clock, zone)
        val snapshot = AlarmState(listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake")), dirty = true, watchPayloads = previous)
        val store = Store(snapshot, address)
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { zone }
        val session = WatchConnectionSession()
        val ready = session.run {
            begin(state.value, this@AlarmPayloadWriterTest.address)
            established(this@AlarmPayloadWriterTest.address)!!
        }
        val records = mutableListOf<AlarmPayloadRecord>()
        val writer = AlarmPayloadWriter(coordinator, AlarmSchedulingTime(clock) { zone }, session) { records += it }
        suspend fun write(read: suspend () -> List<Alarm>, send: suspend (List<Alarm>) -> Unit = {}) =
            writer.write(ready, payload, snapshot, SyncTrigger.MANUAL_SEND_ALARMS, false, {}, read, send)
    }
    @Test fun `confirmation requires matching fresh readback and ignores scratchpad names`() = runTest {
        val s = Scenario()
        CachedIO.put("GET_ALARMS", payload)
        s.write({
            assertTrue(runCatching { CachedIO.get<List<Alarm>>("GET_ALARMS") }.isFailure)
            assertNotNull(s.store.state.writePending)
            assertEquals(s.previous, s.store.state.watchPayloads)
            assertTrue(s.records.isEmpty())
            payload.map { it.copy(name = "") }
        })
        assertNull(s.store.state.writePending)
        assertEquals(7, s.store.state.watchPayloads.getValue(0).hour)
        assertTrue(s.records.single().success)
    }
    @Test fun `mismatch timeout and changed generation discard evidence and record failure`() = runTest {
        for (outcome in listOf("mismatch", "timeout", "generation")) {
            val s = Scenario()
            val result = runCatching { s.write({
                when (outcome) {
                    "timeout" -> delay(ALARM_WRITE_TIMEOUT_MS + 1)
                    "generation" -> s.session.ended(s.ready)
                }
                payload.map { it.copy(minute = 1) }
            }) }
            assertTrue(result.isFailure)
            assertTrue(s.store.state.watchPayloads.isEmpty())
            assertTrue(s.store.state.dirty)
            assertNull(s.store.state.writePending)
            assertFalse(s.records.single().success)
            assertTrue(s.records.single().error!!.contains("unconfirmed"))
        }
    }
    @Test fun `reconnect at two minutes before ring defers fallback until bounded outcome`() = runTest {
        val s = Scenario()
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val write = async { s.write({ reading.complete(Unit); release.await(); payload }) }
        reading.await()
        assertEquals(s.previous, s.store.state.watchPayloads)
        val key = AlarmKey(address, 0)
        assertFalse(s.backend.scheduled.containsKey(key))
        assertFalse(s.coordinator.fallback(key, clock.millis() + 120_000, "07:00:true") { fail("Handed off during write") })
        release.complete(Unit)
        write.await()
        assertFalse(s.backend.scheduled.containsKey(key))
    }
    @Test fun `confirmed daily evidence suppresses fallbacks on following days`() = runTest {
        val s = Scenario()
        s.write({ payload })
        val tomorrow = Clock.fixed(clock.instant().plusSeconds(86_400), zone)
        val coordinator = PersistedAlarmSchedulingCoordinator(s.store, s.backend, tomorrow) { zone }
        coordinator.reconcile(address)
        assertTrue(s.backend.scheduled.isEmpty())
    }
    @Test fun `interrupted write expires old evidence and reconciles fallback after process death`() = runTest {
        val s = Scenario()
        s.store.state = s.snapshot.copy(writePending = AlarmWritePending("interrupted", clock.millis() - 1))
        s.coordinator.reconcile(address)
        assertNull(s.store.state.writePending)
        assertTrue(s.store.state.watchPayloads.isEmpty())
        assertTrue(s.backend.scheduled.containsKey(AlarmKey(address, 0)))
    }
    @Test fun `readback compares enabled and hourly chime bits`() {
        assertFalse(alarmRegistersMatch(payload, payload.map { it.copy(enabled = false) }))
        assertFalse(alarmRegistersMatch(payload, payload.map { it.copy(hasHourlyChime = true) }))
        assertFalse(alarmRegistersMatch(payload, emptyList()))
    }
    @Test fun `confirmed readback clears current warning and retains the outcome as history`() = runTest {
        val s = Scenario()
        val due = clock.millis() + 120_000
        s.store.state = s.snapshot.copy(alarms = s.snapshot.alarms.map {
            it.copy(fallbackStatus = "pending", fallbackStatusAt = due)
        })
        s.write({ payload })
        assertNull(s.store.state.alarms[0].fallbackStatus)
        assertEquals(AlarmFallbackOutcome(0, "pending", due), s.store.state.fallbackHistory.single())
        assertEquals(due, s.store.state.alarms[0].fallbackAttemptAt)
    }

    @Test fun `generation ending during confirmation persistence discards committed evidence`() = runTest {
        val s = Scenario()
        s.store.beforeSave = { state ->
            if (state.writePending == null && state.watchPayloads.isNotEmpty()) s.session.ended(s.ready)
        }
        assertTrue(runCatching { s.write({ payload }) }.isFailure)
        assertTrue(s.store.state.watchPayloads.isEmpty())
        assertTrue(s.store.state.dirty)
        assertFalse(s.records.single().success)
    }

}
