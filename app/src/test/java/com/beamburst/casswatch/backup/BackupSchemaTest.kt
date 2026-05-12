package com.beamburst.casswatch.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSchemaTest {

    @Test
    fun `buildBackupJson wraps entries with schema metadata`() {
        val entries = JSONObject().apply {
            put("key.one", "value one")
            put("key.two", "value two")
        }
        val payload = buildBackupJson(entries, nowMs = 1_700_000_000_000L, versionName = "1.2.3")

        assertEquals(1, payload.getInt(BackupSchema.KEY_SCHEMA))
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
}
