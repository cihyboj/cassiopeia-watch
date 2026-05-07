package com.beamburst.casswatch.utils

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
}
