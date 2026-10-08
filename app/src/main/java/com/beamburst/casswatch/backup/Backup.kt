package com.beamburst.casswatch.backup

import android.content.Context
import android.net.Uri
import com.beamburst.casswatch.BuildConfig
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.ui.alarms.AlarmKey
import com.beamburst.casswatch.ui.alarms.AlarmSyncStorage
import com.beamburst.casswatch.ui.alarms.PhoneFallbackReceiver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Versioned envelope around the raw DataStore entries. Every value the app persists is
 * stored as a string, so we don't need a typed sum — entries is just String→String.
 */
internal object BackupSchema {
    const val VERSION = 2
    const val KEY_SCHEMA = "schemaVersion"
    const val KEY_EXPORTED_AT = "exportedAtMs"
    const val KEY_PACKAGE_VERSION = "packageVersionName"
    const val KEY_ENTRIES = "entries"
}

internal fun buildBackupJson(entries: JSONObject, nowMs: Long, versionName: String): JSONObject =
    JSONObject().apply {
        put(BackupSchema.KEY_SCHEMA, BackupSchema.VERSION)
        put(BackupSchema.KEY_EXPORTED_AT, nowMs)
        put(BackupSchema.KEY_PACKAGE_VERSION, versionName)
        put(BackupSchema.KEY_ENTRIES, entries)
    }

sealed class ImportResult {
    data class Ok(val entryCount: Int) : ImportResult()
    data class Malformed(val message: String) : ImportResult()
    data class UnsupportedVersion(val found: Int) : ImportResult()
    data class IoError(val message: String) : ImportResult()
}

object BackupExporter {
    /**
     * Writes the entire DataStore content as a JSON document to [target]. Throws if the
     * stream cannot be opened or written; callers should report errors via snackbar.
     */
    suspend fun export(context: Context, target: Uri): Int = withContext(Dispatchers.IO) {
        coordinator(context).reconcileAll()
        val entries = LocalDataStorage.toJsonObject(context)
        val payload = buildBackupJson(
            entries = entries,
            nowMs = System.currentTimeMillis(),
            versionName = BuildConfig.VERSION_NAME,
        )
        val stream = context.contentResolver.openOutputStream(target)
            ?: throw IllegalStateException("Cannot open output stream for $target")
        stream.use { it.write(payload.toString(2).toByteArray(Charsets.UTF_8)) }
        entries.length()
    }
}

object BackupImporter {
    suspend fun import(context: Context, source: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(source)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrElse { return@withContext ImportResult.IoError(it.message ?: "open failed") }
            ?: return@withContext ImportResult.IoError("Cannot open input stream for $source")

        parseAndRestore(context, text)
    }

    /**
     * Visible for testing. Parses [text] and writes the entries to DataStore, or
     * returns a non-Ok result describing why it could not.
     */
    internal suspend fun parseAndRestore(context: Context, text: String): ImportResult {
        return restoreBackup(text, object : BackupRestoreTarget {
            override suspend fun replace(entries: JSONObject): Int {
                coordinator(context).replaceAll {
                    LocalDataStorage.restoreFromJsonObject(context, entries)
                    watchSettingsImportKeysToDrop(entries).forEach { LocalDataStorage.deleteAsync(context, it) }
                }
                return entries.length()
            }
        })
    }

    internal fun watchSettingsImportKeysToDrop(entries: JSONObject): Set<String> {
        val watchKeys = buildSet {
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("WatchSettings_")) add(key)
            }
        }
        return watchKeys.filterTo(mutableSetOf()) { key ->
            val cacheKey = key.removeSuffix("_dirty")
            val dirtyKey = "${cacheKey}_dirty"
            val hasDirtyPendingSettings =
                entries.has(cacheKey) &&
                    entries.optString(dirtyKey, "false").toBooleanStrictOrNull() == true
            !hasDirtyPendingSettings
        }
    }
}

private fun coordinator(context: Context) = EntryPointAccessors.fromApplication(
    context.applicationContext, PhoneFallbackReceiver.AlarmEntryPoint::class.java,
).alarmSchedulingCoordinator()

/** Validate the entire envelope and all alarm records before cancelling or writing anything. */
internal fun validatedBackupEntries(payload: JSONObject): JSONObject {
    val schema = payload.get(BackupSchema.KEY_SCHEMA)
    require(schema is Number && schema.toDouble() == schema.toInt().toDouble())
    val version = payload.getInt(BackupSchema.KEY_SCHEMA)
    require(version == 1 || version == 2)
    if (payload.has(BackupSchema.KEY_EXPORTED_AT)) {
        val exportedAt = payload.get(BackupSchema.KEY_EXPORTED_AT)
        require(exportedAt is Number && exportedAt.toDouble() == exportedAt.toLong().toDouble() && exportedAt.toLong() >= 0)
    }
    if (payload.has(BackupSchema.KEY_PACKAGE_VERSION)) require(payload.get(BackupSchema.KEY_PACKAGE_VERSION) is String)
    val entries = payload.getJSONObject(BackupSchema.KEY_ENTRIES)
    entries.keys().asSequence().forEach { key ->
        require(entries.get(key) is String) { "entry '$key' must be a string" }
        val raw = entries.getString(key)
        when {
            key.startsWith("AlarmState_") -> {
                val state = JSONObject(raw)
                require(state.get("dirty") is Boolean)
                validateAlarmRecords(state.getString("alarms"))
                AlarmSyncStorage.decodeDays(state.getString("days"))
                AlarmSyncStorage.decode(raw)
            }
            key == "PhoneAlarmDrafts" || key.startsWith("PhoneAlarmDrafts_") -> validateAlarmRecords(raw)
            key == "AlarmDaySelections" || key.startsWith("AlarmDaySelections_") -> AlarmSyncStorage.decodeDays(raw)
            key == "DeviceAddresses" -> raw.split(',').filter(String::isNotBlank).forEach {
                require(AlarmKey.from(it, 0) != null) { "invalid watch address" }
            }
        }
    }
    return entries
}

private fun validateAlarmRecords(raw: String) {
    val records = org.json.JSONArray(raw)
    for (index in 0 until records.length()) {
        val alarm = records.getJSONObject(index)
        for ((field, range) in listOf("hour" to 0..23, "minute" to 0..59)) {
            val value = alarm.get(field)
            require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in range) { "invalid $field" }
        }
        require(alarm.get("enabled") is Boolean && alarm.get("hasHourlyChime") is Boolean)
        for (field in listOf("firedAt", "fallbackAttemptAt")) {
            if (alarm.has(field) && !alarm.isNull(field)) {
                val value = alarm.get(field)
                require(value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() >= 0) { "invalid $field" }
            }
        }
        if (alarm.has("name") && !alarm.isNull("name")) require(alarm.get("name") is String)
        if (alarm.has("oneShotLocalDate") && !alarm.isNull("oneShotLocalDate")) {
            val value = alarm.get("oneShotLocalDate")
            require(value is String && java.time.LocalDate.parse(value).toString() == value) { "invalid oneShotLocalDate" }
        }
    }
}

internal interface BackupRestoreTarget {
    suspend fun replace(entries: JSONObject): Int
}

internal suspend fun restoreBackup(text: String, target: BackupRestoreTarget): ImportResult {
    val payload = runCatching { JSONObject(text) }
        .getOrElse { return ImportResult.Malformed(it.message ?: "parse error") }
    val version = payload.optInt(BackupSchema.KEY_SCHEMA, -1)
    if (version != 1 && version != BackupSchema.VERSION) return ImportResult.UnsupportedVersion(version)
    val entries = runCatching { validatedBackupEntries(payload) }
        .getOrElse { return ImportResult.Malformed(it.message ?: "invalid backup") }
    return runCatching { ImportResult.Ok(target.replace(entries)) }
        .getOrElse { ImportResult.IoError(it.message ?: "DataStore write failed") }
}
