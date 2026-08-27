package com.beamburst.casswatch.ui.alarms

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.beamburst.casswatch.utils.LocalDataStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.avmedia.gshockapi.Alarm
import java.time.DayOfWeek

object AlarmSyncStorage {
    private const val ALARMS_KEY = "PhoneAlarmDrafts"
    private const val ALARMS_DIRTY_KEY = "PhoneAlarmDraftsDirty"
    private const val ALARM_VIEW_MODE_KEY = "AlarmViewMode"
    private const val ALARM_DAYS_KEY = "AlarmDaySelections"

    private val gson = Gson()
    private val _changes = MutableStateFlow(0L)

    private fun key(base: String, addr: String) = "${base}_$addr"

    data class StoredAlarm(
        val hour: Int,
        val minute: Int,
        val enabled: Boolean,
        val hasHourlyChime: Boolean,
        val name: String?,
        val firedAt: Long? = null
    )

    fun saveAlarms(context: Context, addr: String, alarms: List<Alarm>, dirty: Boolean, firedAts: Map<Int, Long> = emptyMap()) {
        val stored = alarms.mapIndexed { index, it ->
            StoredAlarm(
                hour = it.hour,
                minute = it.minute,
                enabled = it.enabled,
                hasHourlyChime = it.hasHourlyChime,
                name = it.name,
                firedAt = firedAts[index]
            )
        }
        LocalDataStorage.put(context, key(ALARMS_KEY, addr), gson.toJson(stored))
        LocalDataStorage.put(context, key(ALARMS_DIRTY_KEY, addr), dirty.toString())
        _changes.value = System.currentTimeMillis()
    }

    private fun loadStoredAlarms(context: Context, addr: String): List<StoredAlarm>? {
        val json = LocalDataStorage.get(context, key(ALARMS_KEY, addr)) ?: return null
        if (json.isBlank()) return null
        val type = object : TypeToken<List<StoredAlarm>>() {}.type
        return runCatching { gson.fromJson<List<StoredAlarm>>(json, type) }.getOrNull()
    }

    fun loadAlarms(context: Context, addr: String): List<Alarm>? =
        loadStoredAlarms(context, addr)?.map {
            // Coerce out-of-range values (corrupt storage, edited backup import) so
            // downstream LocalTime.of() calls cannot throw.
            Alarm(it.hour.coerceIn(0, 23), it.minute.coerceIn(0, 59), it.enabled, it.hasHourlyChime, it.name)
        }

    fun hasStoredAlarms(context: Context, addr: String): Boolean =
        loadStoredAlarms(context, addr) != null

    fun isDirty(context: Context, addr: String): Boolean =
        LocalDataStorage.get(context, key(ALARMS_DIRTY_KEY, addr), "false")?.toBoolean() ?: false

    fun saveViewMode(context: Context, addr: String, mode: AlarmViewMode) {
        LocalDataStorage.put(context, key(ALARM_VIEW_MODE_KEY, addr), mode.name)
    }

    fun loadViewMode(context: Context, addr: String): AlarmViewMode {
        val stored = LocalDataStorage.get(context, key(ALARM_VIEW_MODE_KEY, addr))
        return if (stored == AlarmViewMode.WEEKLY.name) AlarmViewMode.WEEKLY else AlarmViewMode.SIMPLE
    }

    fun saveDaySelections(context: Context, addr: String, alarmDays: Map<Int, Set<DayOfWeek>>) {
        val toStore = alarmDays
            .mapKeys { it.key.toString() }
            .mapValues { it.value.map { day -> day.name } }
        LocalDataStorage.put(context, key(ALARM_DAYS_KEY, addr), gson.toJson(toStore))
    }

    fun loadDaySelections(context: Context, addr: String): Map<Int, Set<DayOfWeek>> {
        val json = LocalDataStorage.get(context, key(ALARM_DAYS_KEY, addr)) ?: return emptyMap()
        if (json.isBlank()) return emptyMap()

        val type = object : TypeToken<Map<String, List<String>>>() {}.type
        val raw = runCatching {
            gson.fromJson<Map<String, List<String>>>(json, type)
        }.getOrNull() ?: return emptyMap()

        return raw.mapNotNull { (key, values) ->
            val index = key.toIntOrNull() ?: return@mapNotNull null
            val days = values.mapNotNull {
                runCatching { DayOfWeek.valueOf(it) }.getOrNull()
            }.toSet()
            index to days
        }.toMap()
    }

    fun loadFiredAts(context: Context, addr: String): Map<Int, Long> =
        loadStoredAlarms(context, addr)
            ?.mapIndexedNotNull { i, a -> a.firedAt?.let { i to it } }
            ?.toMap()
            ?: emptyMap()

    fun flowFor(context: Context, addr: String): Flow<List<Alarm>?> =
        _changes.map { loadAlarms(context, addr) }.distinctUntilChanged()
}
