package com.beamburst.casswatch.utils

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object LocalDataStorage {

    // Kept for upgrade compatibility so existing users retain persisted preferences.
    private const val STORAGE_NAME = "CASIO_GOOGLE_SYNC_STORAGE"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()

    // Use a DataStore delegate in context
    private val Context.dataStore by preferencesDataStore(
        name = STORAGE_NAME,
        produceMigrations = { context ->
            listOf(SharedPreferencesMigration(context, STORAGE_NAME))
        }
    )

    fun put(context: Context, key: String, value: String) {
        scope.launch {
            mutex.withLock {
                context.dataStore.edit { preferences ->
                    preferences[stringPreferencesKey(key)] = value
                }
            }
        }
    }

    fun get(context: Context, key: String, defaultValue: String? = null): String? {
        var value: String?
        runBlocking {
            mutex.withLock {
                val preferences = context.dataStore.data.first()
                value = preferences[stringPreferencesKey(key)] ?: defaultValue
            }
        }
        return value
    }

    fun delete(context: Context, key: String) {
        scope.launch {
            mutex.withLock {
                deleteAsync(context, key)
            }
        }
    }

    suspend fun deleteAsync(context: Context, key: String) {
        context.dataStore.edit { preferences ->
            preferences.remove(stringPreferencesKey(key))
        }
    }

    suspend fun putAsync(context: Context, key: String, value: String) {
        mutex.withLock {
            context.dataStore.edit { preferences ->
                preferences[stringPreferencesKey(key)] = value
            }
        }
    }

    /**
     * Reads all key-value pairs from the DataStore and returns them as a JSONObject.
     * This is useful for backups or exporting settings.
     *
     * @param context The application context.
     * @return A JSONObject containing all stored preferences.
     */
    fun toJsonObject(context: Context): JSONObject {
        val jsonObject = JSONObject()
        runBlocking {
            mutex.withLock {
                val preferences = context.dataStore.data.first()
                preferences.asMap().forEach { (key, value) ->
                    // We only store strings and string-representations of other types,
                    // so we can safely cast the value.
                    if (value is String) {
                        jsonObject.put(key.name, value)
                    }
                }
            }
        }
        return jsonObject
    }

    fun prettyPrintJson(context: Context): String {
        val jsonIn = toJsonObject(context).toString()
        val prettyGson = GsonBuilder().setPrettyPrinting().create()

        // Parse and then format the existing JSON string
        val jsonElement = prettyGson.fromJson(jsonIn, com.google.gson.JsonElement::class.java)
        val prettyPrintedString = prettyGson.toJson(jsonElement)
        return prettyPrintedString
    }

    /**
     * Replaces ALL stored preferences with the keys in [json]. All values are written as
     * strings (the only type this store uses). Returns the number of entries written.
     * Caller is responsible for restarting the app afterwards so cached values in
     * ViewModels and singletons are reloaded.
     */
    suspend fun restoreFromJsonObject(context: Context, json: JSONObject): Int {
        mutex.withLock {
            context.dataStore.edit { preferences ->
                preferences.clear()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = json.optString(k, "")
                    preferences[stringPreferencesKey(k)] = v
                }
            }
        }
        return json.length()
    }

    private fun getBoolean(context: Context, key: String): Boolean {
        return get(context, key, "false")?.toBoolean() ?: false
    }

    private suspend fun putBoolean(context: Context, key: String, value: Boolean) {
        putAsync(context, key, value.toString())
    }

    fun getTimeAdjustmentNotification(context: Context): Boolean {
        return getBoolean(context, "timeAdjustmentNotification")
    }

    suspend fun setTimeAdjustmentNotification(context: Context, value: Boolean) {
        putBoolean(context, "timeAdjustmentNotification", value)
    }

    fun getFineTimeAdjustment(context: Context): Int {
        return get(context, "fineTimeAdjustment", "0")?.toInt() ?: 0
    }

    suspend fun setFineTimeAdjustment(context: Context, fineTimeAdjustment: Int) {
        putAsync(context, "fineTimeAdjustment", fineTimeAdjustment.toString())
    }

    fun getNtpTimeEnabled(context: Context): Boolean {
        return getBoolean(context, "ntpTimeEnabled")
    }

    fun ntpTimeEnabledFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { prefs ->
            prefs[stringPreferencesKey("ntpTimeEnabled")]?.toBoolean() ?: false
        }

    suspend fun setNtpTimeEnabled(context: Context, enabled: Boolean) {
        putBoolean(context, "ntpTimeEnabled", enabled)
    }

    fun getNtpTimeServer(context: Context): String {
        val stored = get(context, "ntpTimeServer", NtpTimeSettings.DEFAULT_SERVER).orEmpty()
        return NtpTimeSettings.normalizeServer(stored) ?: NtpTimeSettings.DEFAULT_SERVER
    }

    suspend fun setNtpTimeServer(context: Context, server: String) {
        val normalized = NtpTimeSettings.normalizeServer(server) ?: return
        putAsync(context, "ntpTimeServer", normalized)
    }

    fun getNtpOffsetCache(context: Context): NtpOffsetCache? {
        val raw = get(context, "ntpOffsetCache", null) ?: return null
        return parseNtpOffsetCache(raw)
    }

    fun ntpOffsetCacheFlow(context: Context): Flow<NtpOffsetCache?> =
        context.dataStore.data.map { prefs ->
            prefs[stringPreferencesKey("ntpOffsetCache")]?.let(::parseNtpOffsetCache)
        }

    private fun parseNtpOffsetCache(raw: String): NtpOffsetCache? =
        runCatching {
            val json = JSONObject(raw)
            NtpOffsetCache(
                server = json.getString("server"),
                offsetMs = json.getLong("offsetMs"),
                roundTripMs = json.getLong("roundTripMs"),
                capturedElapsedRealtimeMs = json.getLong("capturedElapsedRealtimeMs"),
                ntpTimeAtCaptureMs = json.getLong("ntpTimeAtCaptureMs"),
            )
        }.getOrNull()

    fun getAutoSyncOnReconnect(context: Context, address: String): Boolean? {
        val key = autoSyncOnReconnectKey(address)
        if (get(context, key, null)?.toBooleanStrictOrNull() != true) {
            put(context, key, true.toString())
        }
        return true
    }

    suspend fun setAutoSyncOnReconnect(context: Context, address: String, enabled: Boolean) {
        putBoolean(context, autoSyncOnReconnectKey(address), enabled)
    }

    suspend fun getOrInitializeAutoSyncOnReconnect(
        context: Context,
        address: String,
        seed: Boolean?,
    ): Boolean {
        val current = getAutoSyncOnReconnect(context, address)
        if (current != true) {
            setAutoSyncOnReconnect(context, address, true)
        }
        return true
    }

    suspend fun setNtpOffsetCache(context: Context, cache: NtpOffsetCache) {
        val json = JSONObject()
            .put("server", cache.server)
            .put("offsetMs", cache.offsetMs)
            .put("roundTripMs", cache.roundTripMs)
            .put("capturedElapsedRealtimeMs", cache.capturedElapsedRealtimeMs)
            .put("ntpTimeAtCaptureMs", cache.ntpTimeAtCaptureMs)
        putAsync(context, "ntpOffsetCache", json.toString())
    }

    fun getLearnedTimeWriteCompensation(context: Context, address: String): Long =
        learnedLatencyCompensationMs(
            parseLatencySamples(
                get(context, timeWriteLatencyKey(address), "").orEmpty(),
                nowMs = System.currentTimeMillis(),
            ).map { it.durationMs },
        )

    fun getTimeWriteLatencySamples(context: Context, address: String): List<Long> =
        parseLatencySamples(
            get(context, timeWriteLatencyKey(address), "").orEmpty(),
            nowMs = System.currentTimeMillis(),
        ).map { it.durationMs }
            .takeLast(MAX_LATENCY_SAMPLE_COUNT)

    internal data class LatencySample(val durationMs: Long, val capturedAtMs: Long)

    internal fun parseLatencySamples(raw: String, nowMs: Long): List<LatencySample> =
        raw.split(",")
            .mapNotNull { parseLatencySampleToken(it.trim(), nowMs) }
            .filter { nowMs - it.capturedAtMs <= LATENCY_SAMPLE_RETENTION_MS }
            .takeLast(MAX_LATENCY_SAMPLE_COUNT)

    suspend fun addTimeWriteLatencySample(context: Context, address: String, durationMs: Long) {
        if (durationMs !in MIN_LATENCY_SAMPLE_MS..MAX_LATENCY_SAMPLE_MS) return
        val nowMs = System.currentTimeMillis()
        val samples = parseLatencySamples(
            get(context, timeWriteLatencyKey(address), "").orEmpty(),
            nowMs = nowMs,
        ).plus(LatencySample(durationMs = durationMs, capturedAtMs = nowMs))
            .takeLast(MAX_LATENCY_SAMPLE_COUNT)
        putAsync(context, timeWriteLatencyKey(address), serializeLatencySamples(samples))
    }

    fun timeWriteLatencyKey(address: String): String =
        "TimeWriteLatency_${normalizeDeviceAddress(address)}"

    fun autoSyncOnReconnectKey(address: String): String =
        "AutoSyncOnReconnect_${normalizeDeviceAddress(address)}"

    fun getDeviceAddresses(context: Context): List<String> {
        val addresses = get(context, "DeviceAddresses", "") ?: ""
        return if (addresses.isEmpty()) {
            emptyList()
        } else {
            addresses
                .split(",")
                .map { normalizeDeviceAddress(it) }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
        }
    }

    suspend fun addDeviceAddress(context: Context, address: String) {
        val normalizedAddress = normalizeDeviceAddress(address)
        if (normalizedAddress.isBlank()) return

        val addresses = getDeviceAddresses(context).toMutableList()
        if (addresses.none { it.equals(normalizedAddress, ignoreCase = true) }) {
            addresses.add(normalizedAddress)
            putAsync(context, "DeviceAddresses", addresses.joinToString(","))
        }
    }

    suspend fun removeDeviceAddress(context: Context, address: String) {
        val normalizedAddress = normalizeDeviceAddress(address)
        val addresses = getDeviceAddresses(context).toMutableList()
        if (addresses.removeAll { it.equals(normalizedAddress, ignoreCase = true) }) {
            putAsync(context, "DeviceAddresses", addresses.joinToString(","))
            listOf(address, normalizedAddress, address.lowercase(), address.uppercase())
                .distinct()
                .forEach { deleteAsync(context, "DeviceName_$it") }
        }
    }

    fun getDeviceName(context: Context, address: String): String? {
        val normalizedAddress = normalizeDeviceAddress(address)
        return listOf(normalizedAddress, address, address.lowercase(), address.uppercase())
            .distinct()
            .firstNotNullOfOrNull { get(context, "DeviceName_$it") }
    }

    suspend fun setDeviceName(context: Context, address: String, name: String) {
        val normalizedAddress = normalizeDeviceAddress(address)
        if (normalizedAddress.isBlank()) return
        putAsync(context, "DeviceName_$normalizedAddress", name)
    }

    private fun normalizeDeviceAddress(address: String): String =
        Utils.sanitizeMacAddress(address.trim())

    private fun parseLatencySampleToken(raw: String, nowMs: Long): LatencySample? {
        if (raw.isBlank()) return null
        val split = raw.split("@")
        if (split.size == 2) {
            val durationMs = split[0].toLongOrNull() ?: return null
            val capturedAtMs = split[1].toLongOrNull() ?: return null
            return LatencySample(durationMs, capturedAtMs)
        }
        val legacyDurationMs = raw.toLongOrNull() ?: return null
        return LatencySample(legacyDurationMs, nowMs)
    }

    private fun serializeLatencySamples(samples: List<LatencySample>): String =
        samples.joinToString(",") { "${it.durationMs}@${it.capturedAtMs}" }
}
