package com.beamburst.casswatch.backup

import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.ui.alarms.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AlarmBackupImportTest {
    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"
    private val zone = ZoneId.of("UTC")
    private val clock = Clock.fixed(Instant.parse("2026-10-08T23:00:00Z"), zone)
    private val alarm = AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Wake", oneShotLocalDate = "2026-10-09")
    private class Store(var entries: JSONObject) : AlarmPersistence {
        override suspend fun addresses() = entries.optString("DeviceAddresses", "").split(',').filter(String::isNotBlank)
        override suspend fun load(address: String) = AlarmSyncStorage.readState(address) {
            if (entries.has(it)) entries.getString(it) else null
        }
        override suspend fun save(address: String, state: AlarmState) { entries.put(AlarmSyncStorage.stateKey(address), AlarmSyncStorage.encode(state)) }
        override suspend fun history(address: String): List<SyncEntry> = emptyList()
        override suspend fun legacySlots() = persistedAlarmSlots(entries)
        override suspend fun migrationDone() = entries.optString(ALARM_FALLBACK_MIGRATION_FLAG, "false").toBoolean()
        override suspend fun finishMigration() { entries.put(ALARM_FALLBACK_MIGRATION_FLAG, "true") }
    }
    private class Backend : AlarmScheduleBackend {
        val scheduled = mutableMapOf<AlarmKey, Long>()
        var failOnce = false
        override fun cancel(key: AlarmKey) { scheduled.remove(key) }
        override fun cancelFallback(key: AlarmKey) = cancel(key)
        override fun cancelLegacy(slot: Int) = Unit
        override fun expiry(key: AlarmKey, instant: Long?) = Unit
        override fun schedule(key: AlarmKey, alarm: AlarmSyncStorage.StoredAlarm, instant: Long): FallbackAvailability {
            if (failOnce) { failOnce = false; error("interrupted rebuild") }
            scheduled[key] = instant
            return FallbackAvailability.SCHEDULED
        }
    }
    private fun entries(vararg addresses: String) = JSONObject().apply {
        put("DeviceAddresses", addresses.joinToString(","))
        put(ALARM_FALLBACK_MIGRATION_FLAG, "true")
        addresses.forEach { put(AlarmSyncStorage.stateKey(it), AlarmSyncStorage.encode(AlarmState(listOf(alarm), mapOf(0 to emptySet()), true))) }
    }
    private fun imported(version: Int, consumed: Boolean = false): JSONObject {
        val data = entries(a)
        if (version == 1) {
            data.remove(AlarmSyncStorage.stateKey(a))
            data.put("PhoneAlarmDrafts_$a", """[{"hour":7,"minute":0,"enabled":true,"hasHourlyChime":false,"name":"Wake"${if (consumed) ",\"firedAt\":123" else ""}}]""")
            data.put("AlarmDaySelections_$a", """{"0":[]}""")
        } else if (consumed) {
            data.put(AlarmSyncStorage.stateKey(a), AlarmSyncStorage.encode(AlarmState(listOf(alarm.copy(firedAt = 123)), mapOf(0 to emptySet()), true)))
        }
        return buildBackupJson(data, 0, "test").put(BackupSchema.KEY_SCHEMA, version)
    }
    @Test fun `v1 and v2 import migrate rebuild and cancel watches absent from backup`() = runBlocking {
        for (version in listOf(1, 2)) {
            val store = Store(entries(a, b))
            val backend = Backend()
            val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { zone }
            coordinator.reconcileAll()
            assertTrue(backend.scheduled.containsKey(AlarmKey(b, 0)))
            val result = restoreBackup(imported(version).toString(), object : BackupRestoreTarget {
                override suspend fun replace(entries: JSONObject): Int {
                    coordinator.replaceAll { store.entries = backupEntriesForRestore(entries) }
                    return entries.length()
                }
            })
            assertTrue(result is ImportResult.Ok)
            assertEquals("2026-10-09", store.load(a).alarms[0].oneShotLocalDate)
            assertEquals(listOf(a), store.addresses())
            assertEquals(setOf(AlarmKey(a, 0)), backend.scheduled.keys)
        }
    }
    @Test fun `consumed one time alarm stays consumed in both import versions`() = runBlocking {
        for (version in listOf(1, 2)) {
            val store = Store(entries(b))
            val backend = Backend()
            val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { zone }
            val result = restoreBackup(imported(version, consumed = true).toString(), object : BackupRestoreTarget {
                override suspend fun replace(entries: JSONObject): Int {
                    coordinator.replaceAll { store.entries = backupEntriesForRestore(entries) }
                    return entries.length()
                }
            })
            assertTrue(result is ImportResult.Ok)
            assertEquals(123L, store.load(a).alarms[0].firedAt)
            assertTrue(backend.scheduled.isEmpty())
        }
    }
    @Test fun `v2 export round trip preserves date and consumed marker`() {
        val original = AlarmState(listOf(alarm.copy(firedAt = 123)), mapOf(0 to emptySet()), true)
        val data = JSONObject().put(AlarmSyncStorage.stateKey(a), AlarmSyncStorage.encode(original))
        val backup = buildBackupJson(data, 0, "test")
        assertEquals(2, backup.getInt(BackupSchema.KEY_SCHEMA))
        assertEquals(original, AlarmSyncStorage.readState(a) { validatedBackupEntries(backup).optString(it, null) })
    }
    @Test fun `malformed date invalid time and non string entries are rejected before mutation`() = runBlocking {
        val malformed = listOf(
            JSONObject().put("PhoneAlarmDrafts_$a", """[{"hour":7,"minute":0,"enabled":true,"hasHourlyChime":false,"oneShotLocalDate":"2026-02-30"}]"""),
            JSONObject().put("PhoneAlarmDrafts_$a", """[{"hour":24,"minute":0,"enabled":true,"hasHourlyChime":false}]"""),
            JSONObject().put("AlarmDaySelections_$a", """{"0":["FUNDAY"]}"""),
            JSONObject().put("not-a-string", JSONObject()),
        )
        var mutations = 0
        val target = object : BackupRestoreTarget {
            override suspend fun replace(entries: JSONObject): Int { mutations++; return entries.length() }
        }
        for (data in malformed) assertTrue(restoreBackup(buildBackupJson(data, 0, "test").toString(), target) is ImportResult.Malformed)
        assertEquals(0, mutations)
    }
    @Test fun `interrupted import rebuild leaves done flag clear and startup retries`() = runBlocking {
        val store = Store(entries(b))
        val backend = Backend()
        val coordinator = PersistedAlarmSchedulingCoordinator(store, backend, clock) { zone }
        coordinator.reconcileAll()
        backend.failOnce = true
        val result = restoreBackup(imported(2).toString(), object : BackupRestoreTarget {
            override suspend fun replace(entries: JSONObject): Int {
                coordinator.replaceAll { store.entries = backupEntriesForRestore(entries) }
                return entries.length()
            }
        })
        assertTrue(result is ImportResult.IoError)
        assertFalse(store.migrationDone())
        coordinator.reconcileAll()
        assertTrue(store.migrationDone())
        assertEquals(setOf(AlarmKey(a, 0)), backend.scheduled.keys)
    }
    @Test fun `import keeps display history but clears confirmation and phone local receipts`() {
        val original = AlarmState(listOf(alarm.copy(fallbackAttemptAt = clock.millis(), fallbackStatus = "requested")),
            watchPayloads = mapOf(0 to WatchAlarmPayload(a, 7, 0, true, true, zone.id, 0, clock.millis())))
        val data = JSONObject().put(AlarmSyncStorage.stateKey(a), AlarmSyncStorage.encode(original)).put("SyncHistory_$a", "display history")
        val restored = backupEntriesForRestore(data)
        val state = AlarmSyncStorage.decode(restored.getString(AlarmSyncStorage.stateKey(a)))
        assertTrue(state.watchPayloads.isEmpty())
        assertNull(state.alarms[0].fallbackAttemptAt)
        assertNull(state.alarms[0].fallbackStatus)
        assertEquals("display history", restored.getString("SyncHistory_$a"))
    }
}
