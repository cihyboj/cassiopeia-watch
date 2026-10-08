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
    private fun evaluate(hour: Int = 12, minute: Int = 10, slot: Int = 0, payloads: Map<Int, WatchAlarmPayload> = emptyMap(), auto: Boolean = true) =
        AlarmSyncRiskEvaluator.evaluate(AlarmKey(address, slot), AlarmSyncStorage.StoredAlarm(hour, minute, true, false, "Lunch"),
            AlarmSchedulePlanner.AllDays, payloads, clock, zone, auto, 30)
    private fun payload(address: String = this.address, hour: Int = 12, minute: Int = 10) =
        WatchAlarmPayload(address, hour, minute, true, true, zone.id, 0, clock.millis() - 1000)

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
        assertNull(evaluate(payloads = mapOf(0 to payload())))
    }
    @Test fun `same hash in another slot or watch does not suppress fallback`() {
        assertNotNull(evaluate(slot = 1, payloads = mapOf(0 to payload())))
        assertNotNull(evaluate(payloads = mapOf(0 to payload("AA:BB:CC:DD:EE:02"))))
    }
    @Test fun `failed sync and future record do not suppress fallback`() {
        assertNotNull(evaluate(payloads = emptyMap()))
        assertNotNull(evaluate(payloads = mapOf(0 to payload().copy(syncedAt = clock.millis() + 1000))))
    }
    @Test fun `automatic sync disabled has no prediction`() {
        assertNull(evaluate(auto = false)?.nextAutoSyncAt)
        assertNotNull(evaluate(auto = false))
    }
    @Test fun `DST gap sync evidence matches effective payload rather than missing wall time`() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        val beforeGap = Clock.fixed(Instant.parse("2026-03-28T23:00:00Z"), warsaw)
        val draft = AlarmSyncStorage.StoredAlarm(2, 30, true, false, "Gap", oneShotLocalDate = "2026-03-29")
        val recorded = payload(hour = 3, minute = 30).copy(zoneId = warsaw.id, utcOffsetSeconds = 7200, syncedAt = beforeGap.millis() - 1000)
        assertNull(AlarmSyncRiskEvaluator.evaluate(AlarmKey(address, 0), draft, emptySet(), mapOf(0 to recorded), beforeGap, warsaw, false, 0))
        assertNotNull(AlarmSyncRiskEvaluator.evaluate(AlarmKey(address, 0), draft, emptySet(),
            mapOf(0 to recorded.copy(hour = 2)), beforeGap, warsaw, false, 0))
    }
}
