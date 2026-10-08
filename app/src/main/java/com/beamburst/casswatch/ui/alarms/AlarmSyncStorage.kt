package com.beamburst.casswatch.ui.alarms

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.Utils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.avmedia.gshockapi.Alarm
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import timber.log.Timber

data class AlarmState(
    val alarms: List<AlarmSyncStorage.StoredAlarm> = emptyList(),
    val days: Map<Int, Set<DayOfWeek>> = emptyMap(),
    val dirty: Boolean = false,
    val watchPayloads: Map<Int, WatchAlarmPayload> = emptyMap(),
) {
    fun watchAlarms(): List<Alarm> = alarms.map { Alarm(it.hour, it.minute, it.enabled, it.hasHourlyChime, it.name) }
    fun oneShotOccurrences(): Map<Int, AlarmOccurrence> = alarms.mapIndexedNotNull { i, alarm ->
        if (days[i]?.isEmpty() == true) alarm.occurrence()?.let { i to it } else null
    }.toMap()
    fun oneShotLocalDates(): Map<Int, String> = alarms.mapIndexedNotNull { i, a -> a.oneShotLocalDate?.let { i to it } }.toMap()
    fun firedAts(): Map<Int, Long> = alarms.mapIndexedNotNull { i, a -> a.firedAt?.let { i to it } }.toMap()
}

/** Actual daily alarm registers last written to this watch; history is display-only. */
data class WatchAlarmPayload(
    val address: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val daily: Boolean,
    val zoneId: String,
    val utcOffsetSeconds: Int,
    val syncedAt: Long,
)

internal const val ALARM_FALLBACK_MIGRATION_FLAG = "AlarmFallbackIdentityMigrated"

object AlarmSyncStorage {
    private val gson = Gson()
    private val changes = MutableStateFlow(0L)
    private fun key(base: String, addr: String) = "${base}_${Utils.sanitizeMacAddress(addr)}"
    fun stateKey(addr: String) = key("AlarmState", addr)

    // This existing model has an R8 field keep rule. Dates stay ISO strings in storage.
    data class StoredAlarm(
        val hour: Int,
        val minute: Int,
        val enabled: Boolean,
        val hasHourlyChime: Boolean,
        val name: String?,
        val firedAt: Long? = null,
        val oneShotLocalDate: String? = null,
        val fallbackAttemptAt: Long? = null,
        val fallbackStatus: String? = null,
    )

    internal fun encode(state: AlarmState): String = JSONObject().apply {
        put("alarms", gson.toJson(state.alarms))
        put("days", encodeDays(state.days))
        put("dirty", state.dirty)
        put("watchPayloads", JSONObject().apply {
            state.watchPayloads.forEach { (slot, payload) ->
                put(slot.toString(), JSONObject().apply {
                    put("address", payload.address)
                    put("hour", payload.hour)
                    put("minute", payload.minute)
                    put("enabled", payload.enabled)
                    put("daily", payload.daily)
                    put("zoneId", payload.zoneId)
                    put("utcOffsetSeconds", payload.utcOffsetSeconds)
                    put("syncedAt", payload.syncedAt)
                })
            }
        })
    }.toString()

    internal fun decode(raw: String): AlarmState {
        val obj = JSONObject(raw)
        val payloads = obj.optJSONObject("watchPayloads") ?: JSONObject()
        val decoded = payloads.keys().asSequence().associate { key ->
            val slot = key.toInt().also { require(it >= 0) }
            val p = payloads.getJSONObject(key)
            slot to WatchAlarmPayload(p.getString("address"), p.getInt("hour"), p.getInt("minute"),
                p.getBoolean("enabled"), p.getBoolean("daily"), p.getString("zoneId"),
                p.getInt("utcOffsetSeconds"), p.getLong("syncedAt")).also {
                require(AlarmKey.from(it.address, slot)?.normalizedAddress == it.address)
                require(it.hour in 0..23 && it.minute in 0..59 && it.syncedAt >= 0)
                ZoneId.of(it.zoneId)
                java.time.ZoneOffset.ofTotalSeconds(it.utcOffsetSeconds)
            }
        }
        return AlarmState(decodeAlarms(obj.getString("alarms")), decodeDays(obj.getString("days")),
            obj.getBoolean("dirty"), decoded)
    }

    internal fun decodeAlarms(raw: String): List<StoredAlarm> {
        val array = org.json.JSONArray(raw)
        for (index in 0 until array.length()) {
            val alarm = array.getJSONObject(index)
            for ((field, range) in listOf("hour" to 0..23, "minute" to 0..59)) {
                val value = alarm.get(field)
                require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in range)
            }
            require(alarm.get("enabled") is Boolean && alarm.get("hasHourlyChime") is Boolean)
            if (alarm.has("oneShotLocalDate") && !alarm.isNull("oneShotLocalDate")) {
                val date = alarm.getString("oneShotLocalDate")
                require(LocalDate.parse(date).toString() == date)
            }
            for (field in listOf("firedAt", "fallbackAttemptAt")) {
                if (alarm.has(field) && !alarm.isNull(field)) {
                    val value = alarm.get(field)
                    require(value is Number && value.toDouble() == value.toLong().toDouble() && value.toLong() >= 0)
                }
            }
        }
        return gson.fromJson(raw, object : TypeToken<List<StoredAlarm>>() {}.type)
    }

    private fun encodeDays(days: Map<Int, Set<DayOfWeek>>): String = gson.toJson(
        days.mapKeys { it.key.toString() }.mapValues { it.value.map(DayOfWeek::name) }
    )

    internal fun decodeDays(raw: String): Map<Int, Set<DayOfWeek>> {
        val obj = JSONObject(raw)
        return obj.keys().asSequence().associate { key ->
            val index = key.toInt().also { require(it >= 0) }
            val arr = obj.getJSONArray(key)
            index to (0 until arr.length()).map { DayOfWeek.valueOf(arr.getString(it)) }.toSet()
        }
    }

    internal fun loadState(context: Context, addr: String): AlarmState = readState(addr) { LocalDataStorage.get(context, it) }

    /** Called inside the coordinator lock. Keep the original bytes for diagnosis/recovery. */
    internal suspend fun loadRecovering(context: Context, addr: String): AlarmState {
        val raw = LocalDataStorage.get(context, stateKey(addr))
        if (raw != null && runCatching { decode(raw) }.isFailure) {
            Timber.w("Quarantining malformed alarm state for %s", addr)
            LocalDataStorage.putAsync(context, "Quarantined${stateKey(addr)}_${System.currentTimeMillis()}", raw)
            saveState(context, addr, AlarmState())
            return AlarmState()
        }
        return loadState(context, addr)
    }

    internal fun readState(addr: String, read: (String) -> String?): AlarmState {
        read(stateKey(addr))?.let { raw ->
            return runCatching { decode(raw) }.getOrElse {
                Timber.w(it, "Malformed alarm state for %s; awaiting quarantine", addr)
                AlarmState()
            }
        }
        val alarms = read(key("PhoneAlarmDrafts", addr))
            ?.takeIf(String::isNotBlank)?.let { runCatching { decodeAlarms(it) }.getOrNull() }.orEmpty()
            .map { it.copy(hour = it.hour.coerceIn(0, 23), minute = it.minute.coerceIn(0, 59)) }
        val days = read(key("AlarmDaySelections", addr))
            ?.let { runCatching { decodeDays(it) }.getOrNull() }.orEmpty()
        return AlarmState(alarms, days, read(key("PhoneAlarmDraftsDirty", addr)).toBoolean())
    }

    /** One awaited DataStore transaction contains the draft, days, date, consumption and dirty state. */
    internal suspend fun saveState(context: Context, addr: String, state: AlarmState) {
        LocalDataStorage.putAsync(context, stateKey(addr), encode(state))
        changes.update { it + 1 }
    }

    suspend fun saveAlarms(context: Context, addr: String, alarms: List<Alarm>, dirty: Boolean, firedAts: Map<Int, Long> = loadFiredAts(context, addr)) {
        val old = loadState(context, addr)
        val stored = alarms.mapIndexed { index, a ->
            StoredAlarm(a.hour, a.minute, a.enabled, a.hasHourlyChime, a.name, firedAts[index],
                old.alarms.getOrNull(index)?.oneShotLocalDate, old.alarms.getOrNull(index)?.fallbackAttemptAt,
                old.alarms.getOrNull(index)?.fallbackStatus)
        }
        saveState(context, addr, old.copy(alarms = stored, dirty = dirty))
    }

    fun loadAlarms(context: Context, addr: String): List<Alarm>? = loadState(context, addr).watchAlarms().takeIf { it.isNotEmpty() }
    fun hasStoredAlarms(context: Context, addr: String): Boolean = loadAlarms(context, addr) != null
    fun isDirty(context: Context, addr: String): Boolean = loadState(context, addr).dirty
    suspend fun saveViewMode(context: Context, addr: String, mode: AlarmViewMode) = LocalDataStorage.putAsync(context, key("AlarmViewMode", addr), mode.name)
    fun loadViewMode(context: Context, addr: String): AlarmViewMode =
        if (LocalDataStorage.get(context, key("AlarmViewMode", addr)) == AlarmViewMode.WEEKLY.name) AlarmViewMode.WEEKLY else AlarmViewMode.SIMPLE
    suspend fun saveDaySelections(context: Context, addr: String, alarmDays: Map<Int, Set<DayOfWeek>>) =
        saveState(context, addr, loadState(context, addr).copy(days = alarmDays))
    fun loadDaySelections(context: Context, addr: String): Map<Int, Set<DayOfWeek>> = loadState(context, addr).days
    fun loadFiredAts(context: Context, addr: String): Map<Int, Long> = loadState(context, addr).firedAts()
    fun flowFor(context: Context, addr: String): Flow<List<Alarm>?> = changes.map { loadAlarms(context, addr) }.distinctUntilChanged()
    internal fun stateFlowFor(context: Context, addr: String): Flow<AlarmState> = changes.map { loadState(context, addr) }.distinctUntilChanged()
}

/** Includes orphaned drafts and sparse legacy day-selection indices, not just the paired list. */
internal fun persistedAlarmSlots(entries: JSONObject): Set<Int> = buildSet {
    entries.keys().asSequence().forEach { key ->
        runCatching {
            when {
                key.startsWith("AlarmState_") -> {
                    val state = AlarmSyncStorage.decode(entries.getString(key))
                    addAll(state.alarms.indices)
                    addAll(state.days.keys)
                }
                key == "PhoneAlarmDrafts" || key.startsWith("PhoneAlarmDrafts_") ->
                    addAll(AlarmSyncStorage.decodeAlarms(entries.getString(key)).indices)
                key == "AlarmDaySelections" || key.startsWith("AlarmDaySelections_") ->
                    addAll(AlarmSyncStorage.decodeDays(entries.getString(key)).keys)
            }
        }
    }
}
