package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmSyncRiskEvaluatorTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-05-06T10:00:00Z"), zone)
    private fun evaluate(hour: Int = 12, minute: Int = 10, slot: Int = 0, history: List<SyncEntry> = emptyList(), auto: Boolean = true) =
        AlarmSyncRiskEvaluator.evaluate(AlarmKey(address, slot), AlarmSyncStorage.StoredAlarm(hour, minute, true, false, "Lunch"),
            AlarmSchedulePlanner.AllDays, history, clock, zone, auto, 30)
    private fun success(address: String = this.address, hashes: List<String> = listOf("12:10:true")) = SyncEntry(
        clock.millis() - 1000, address, SyncTrigger.MANUAL_SEND_ALARMS, setOf(SyncKind.ALARMS), true, errorMessage = null,
        details = SyncDetails.Empty.copy(alarmHashes = hashes))

    @Test fun `warns before auto sync`() {
        val risk = evaluate()
        assertEquals(LocalDateTime.of(2026, 5, 6, 12, 10), risk?.nextAlarmAt)
        assertEquals(LocalDateTime.of(2026, 5, 6, 12, 30), risk?.nextAutoSyncAt)
    }
    @Test fun `predicted auto sync before alarm never suppresses fallback`() {
        assertNotNull(evaluate(13, 0))
        assertNotNull(evaluate(12, 30))
    }
    @Test fun `actual successful sync in same watch and slot suppresses fallback`() {
        assertNull(evaluate(history = listOf(success())))
    }
    @Test fun `same hash in another slot or watch does not suppress fallback`() {
        assertNotNull(evaluate(slot = 1, history = listOf(success())))
        assertNotNull(evaluate(history = listOf(success("AA:BB:CC:DD:EE:02"))))
    }
    @Test fun `failed sync and future record do not suppress fallback`() {
        assertNotNull(evaluate(history = listOf(success().copy(success = false))))
        assertNotNull(evaluate(history = listOf(success().copy(timestamp = clock.millis() + 1000))))
    }
    @Test fun `automatic sync disabled has no prediction`() {
        assertNull(evaluate(auto = false)?.nextAutoSyncAt)
        assertNotNull(evaluate(auto = false))
    }
}
