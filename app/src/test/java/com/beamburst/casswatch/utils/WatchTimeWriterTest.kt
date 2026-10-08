package com.beamburst.casswatch.utils

import com.beamburst.casswatch.data.local.*
import com.beamburst.casswatch.ui.alarms.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.avmedia.gshockapi.Alarm
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.WatchInfo
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

class WatchTimeWriterTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-09T06:58:00Z"), zone)
    private class Store(val address: String, var state: AlarmState) : AlarmPersistence {
        override suspend fun addresses() = listOf(address)
        override suspend fun load(address: String) = state
        override suspend fun save(address: String, state: AlarmState) { this.state = state }
        override suspend fun history(address: String): List<SyncEntry> = emptyList()
        override suspend fun legacySlots(): Set<Int> = emptySet()
        override suspend fun migrationDone() = true
        override suspend fun finishMigration() = Unit
    }
    private class Backend : AlarmScheduleBackend {
        var scheduled = false
        override fun cancel(key: AlarmKey) { scheduled = false }
        override fun cancelFallback(key: AlarmKey) = cancel(key)
        override fun cancelLegacy(slot: Int) = Unit
        override fun expiry(key: AlarmKey, instant: Long?) = Unit
        override fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long): FallbackAvailability {
            scheduled = true
            return FallbackAvailability.SCHEDULED
        }
    }
    @Test fun `production prime cold and both warm write paths allow more than eight seconds`() = runTest {
        val session = WatchConnectionSession()
        session.begin(session.state.value, address)
        val ready = session.established(address)!!
        val tracker = WatchConnectionTracker(session)
        val store = Store(address, AlarmState(clockNeedsSync = true))
        val coordinator = PersistedAlarmSchedulingCoordinator(store, Backend(), clock) { zone }
        val writer = WatchTimeWriter(session, tracker, coordinator, { _, _ -> }, { 100L }, { _, _ -> }, { testScheduler.currentTime })
        @Suppress("UNCHECKED_CAST")
        val api = Proxy.newProxyInstance(IGShockAPI::class.java.classLoader, arrayOf(IGShockAPI::class.java)) { _, method, args ->
            when (method.name) {
                "isConnected" -> true
                "setTime", "primeTimeCache" -> {
                    val operation: suspend () -> Unit = { delay(10_000) }
                    operation.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Unit>)
                }
                else -> error("Unexpected ${method.name}")
            }
        } as IGShockAPI
        val time = WatchTime(null, 0, 0, "Phone")
        writer.write(api, time, ready)
        assertFalse(store.state.clockNeedsSync)
        assertEquals(WatchTimeWriter.PrimeOutcome.OK, writer.primeCache(api).outcome)
        assertTrue(tracker.isCacheWarm(address))
        store.state = store.state.copy(clockNeedsSync = true)
        writer.write(api, time, ready)
        assertFalse(store.state.clockNeedsSync)
        writer.writeAfterPrime(api, time, ready)
        assertTrue(testScheduler.currentTime >= 40_000)
    }
    @Test fun `clock change then Send Time repairs clock and restores alarm evidence with unchanged chime`() = runTest {
        val session = WatchConnectionSession()
        session.begin(session.state.value, address)
        val ready = session.established(address)!!
        val alarms = arrayListOf(Alarm(7, 0, true, false, "Wake"))
        val initial = AlarmState(listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake")),
            watchPayloads = watchPayloadsAfterWrite(address, alarms, clock, zone))
        val store = Store(address, alarmStateAfterPhoneClockChange(initial))
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { zone }
        val writer = WatchTimeWriter(session, WatchConnectionTracker(session), coordinator, { _, _ -> }, { 100L }, { _, _ -> }, { testScheduler.currentTime })
        val records = mutableListOf<AlarmPayloadRecord>()
        val payloadWriter = AlarmPayloadWriter(coordinator, AlarmSchedulingTime(clock) { zone }, session) { records += it }
        var received = alarms
        val api = Proxy.newProxyInstance(IGShockAPI::class.java.classLoader, arrayOf(IGShockAPI::class.java)) { _, method, args ->
            when (method.name) {
                "isConnected" -> true
                "setTime" -> Unit
                "getAlarms" -> received
                "setAlarms" -> { @Suppress("UNCHECKED_CAST") received = args!![0] as ArrayList<Alarm>; Unit }
                else -> error("Unexpected ${method.name}")
            }
        } as IGShockAPI
        coordinator.reconcile(address)
        assertTrue(backend.scheduled)
        writer.write(api, WatchTime(null, 0, 0, "Phone"), ready)
        assertFalse(store.state.clockNeedsSync)
        val old = WatchInfo.chimeInSettings
        try {
            WatchInfo.chimeInSettings = false
            payloadWriter.hourlySignal(api, ready, HourlySignalSettings.Default,
                java.time.LocalDateTime.ofInstant(clock.instant(), zone), SyncTrigger.MANUAL_SET_TIME, {})
        } finally { WatchInfo.chimeInSettings = old }
        assertTrue(records.single().success)
        assertFalse(store.state.dirty)
        assertFalse(store.state.clockNeedsSync)
        assertFalse(backend.scheduled)
        assertFalse(store.state.confirmedPayloads().isEmpty())
        // Later hourly-signal failure does not undo the confirmed time operation.
        assertTrue(runCatching { error("Hourly signal transport failed") }.isFailure)
        assertFalse(store.state.clockNeedsSync)
    }
}
