package com.beamburst.casswatch.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSchemaTest {

    @Test
    fun `export excludes NTP samples without changing preferences`() {
        val entries = JSONObject().put("ntpOffsetCache", "old sample").put("ntpTimeEnabled", "true")
        val exported = buildBackupJson(entries, 0, "test").getJSONObject(BackupSchema.KEY_ENTRIES)
        assertFalse(exported.has("ntpOffsetCache"))
        assertEquals("true", exported.getString("ntpTimeEnabled"))
        assertTrue(entries.has("ntpOffsetCache"))
    }

    @Test
    fun `both import schemas ignore NTP samples`() = kotlinx.coroutines.test.runTest {
        for (version in listOf(1, 2)) {
            val entries = JSONObject().put("ntpOffsetCache", "old sample").put("ntpTimeServer", "pool.ntp.org")
            val payload = buildBackupJson(entries, 0, "test").put(BackupSchema.KEY_SCHEMA, version)
                .put(BackupSchema.KEY_ENTRIES, entries)
            val result = restoreBackup(payload.toString(), object : BackupRestoreTarget {
                override suspend fun replace(entries: JSONObject): Int {
                    assertFalse(entries.has("ntpOffsetCache"))
                    val restored = backupEntriesForRestore(entries)
                    assertFalse(restored.has("ntpOffsetCache"))
                    assertEquals("pool.ntp.org", restored.getString("ntpTimeServer"))
                    return restored.length()
                }
            })
            assertTrue(result is ImportResult.Ok)
        }
    }

    @Test
    fun `buildBackupJson wraps entries with schema metadata`() {
        val entries = JSONObject().apply {
            put("key.one", "value one")
            put("key.two", "value two")
        }
        val payload = buildBackupJson(entries, nowMs = 1_700_000_000_000L, versionName = "1.2.3")

        assertEquals(2, payload.getInt(BackupSchema.KEY_SCHEMA))
        assertEquals(1_700_000_000_000L, payload.getLong(BackupSchema.KEY_EXPORTED_AT))
        assertEquals("1.2.3", payload.getString(BackupSchema.KEY_PACKAGE_VERSION))
        val readBack = payload.getJSONObject(BackupSchema.KEY_ENTRIES)
        assertEquals("value one", readBack.getString("key.one"))
        assertEquals("value two", readBack.getString("key.two"))
    }

    @Test
    fun `round-trip preserves string entries verbatim`() {
        val original = JSONObject().apply {
            put("plain", "hello")
            put("json-blob", """{"nested":42,"array":[1,2,3]}""")
            put("unicode", "naïve über 日本語")
            put("empty", "")
        }
        val payload = buildBackupJson(original, 0L, "test")
        val asString = payload.toString()
        val reparsed = JSONObject(asString).getJSONObject(BackupSchema.KEY_ENTRIES)
        assertEquals("hello", reparsed.getString("plain"))
        assertEquals("""{"nested":42,"array":[1,2,3]}""", reparsed.getString("json-blob"))
        assertEquals("naïve über 日本語", reparsed.getString("unicode"))
        assertEquals("", reparsed.getString("empty"))
    }

    @Test
    fun `import drops clean watch settings caches`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        val entries = JSONObject().apply {
            put("WatchSettings_$addr", """{"adjustmentTimeMinutes":15}""")
            put("WatchSettings_${addr}_dirty", "false")
            put("AutoSyncOnReconnect_$addr", "false")
        }

        val keysToDrop = BackupImporter.watchSettingsImportKeysToDrop(entries)

        assertTrue("WatchSettings_$addr" in keysToDrop)
        assertTrue("WatchSettings_${addr}_dirty" in keysToDrop)
        assertFalse("AutoSyncOnReconnect_$addr" in keysToDrop)
    }

    @Test
    fun `import preserves dirty watch settings caches`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        val entries = JSONObject().apply {
            put("WatchSettings_$addr", """{"adjustmentTimeMinutes":15}""")
            put("WatchSettings_${addr}_dirty", "true")
        }

        val keysToDrop = BackupImporter.watchSettingsImportKeysToDrop(entries)

        assertFalse("WatchSettings_$addr" in keysToDrop)
        assertFalse("WatchSettings_${addr}_dirty" in keysToDrop)
    }

    @Test
    fun `import drops orphan dirty watch settings flags`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        val entries = JSONObject().apply {
            put("WatchSettings_${addr}_dirty", "true")
        }

        val keysToDrop = BackupImporter.watchSettingsImportKeysToDrop(entries)

        assertTrue("WatchSettings_${addr}_dirty" in keysToDrop)
    }
}
