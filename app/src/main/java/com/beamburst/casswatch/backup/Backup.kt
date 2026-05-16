package com.beamburst.casswatch.backup

import android.content.Context
import android.net.Uri
import com.beamburst.casswatch.BuildConfig
import com.beamburst.casswatch.utils.LocalDataStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Versioned envelope around the raw DataStore entries. Every value the app persists is
 * stored as a string, so we don't need a typed sum — entries is just String→String.
 */
internal object BackupSchema {
    const val VERSION = 1
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
        val payload = runCatching { JSONObject(text) }
            .getOrElse { return ImportResult.Malformed(it.message ?: "parse error") }
        val version = payload.optInt(BackupSchema.KEY_SCHEMA, -1)
        if (version != BackupSchema.VERSION) return ImportResult.UnsupportedVersion(version)
        val entries = payload.optJSONObject(BackupSchema.KEY_ENTRIES)
            ?: return ImportResult.Malformed("missing '${BackupSchema.KEY_ENTRIES}' object")
        val count = runCatching {
            LocalDataStorage.restoreFromJsonObject(context, entries)
        }.getOrElse { return ImportResult.IoError(it.message ?: "DataStore write failed") }

        // Wipe watch-settings caches so stale backup values are never pushed to the watch.
        // The app will re-read current settings from the watch on next connection.
        val watchKeys = buildList {
            val ks = entries.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                if (k.startsWith("WatchSettings_")) add(k)
            }
        }
        watchKeys.forEach { LocalDataStorage.deleteAsync(context, it) }

        return ImportResult.Ok(count)
    }
}
