package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import java.time.DayOfWeek

data class HourlySignalSettings(
    val enabled: Boolean,
    val startHour: Int,  // ∈ {1, 7, 13, 19}
    val endHour: Int,    // ∈ {6, 12, 18, 0}  (0 represents 24 / end-of-day)
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    companion object {
        val Default = HourlySignalSettings(
            enabled = false,
            startHour = 7,
            endHour = 18,
            days = DayOfWeek.entries.toSet(),
        )

        private const val KEY_ENABLED = "HourlySignalEnabled"
        private const val KEY_START = "HourlySignalStartHour"
        private const val KEY_END = "HourlySignalEndHour"
        private const val KEY_MIGRATED = "HourlySignalMigrated"
        private const val KEY_DAYS = "HourlySignalDays"

        private fun key(base: String, addr: String) = "${base}_$addr"

        fun load(context: Context, addr: String): HourlySignalSettings = HourlySignalSettings(
            enabled = LocalDataStorage.get(context, key(KEY_ENABLED, addr), "false")?.toBoolean() ?: false,
            startHour = LocalDataStorage.get(context, key(KEY_START, addr), "7")?.toIntOrNull() ?: 7,
            endHour = LocalDataStorage.get(context, key(KEY_END, addr), "18")?.toIntOrNull() ?: 18,
            days = LocalDataStorage.get(context, key(KEY_DAYS, addr), null)
                ?.let { parseDays(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: DayOfWeek.entries.toSet(),
        )

        fun save(context: Context, addr: String, settings: HourlySignalSettings) {
            LocalDataStorage.put(context, key(KEY_ENABLED, addr), settings.enabled.toString())
            LocalDataStorage.put(context, key(KEY_START, addr), settings.startHour.toString())
            LocalDataStorage.put(context, key(KEY_END, addr), settings.endHour.toString())
            LocalDataStorage.put(context, key(KEY_DAYS, addr), serializeDays(settings.days))
        }

        fun isMigrated(context: Context, addr: String): Boolean =
            LocalDataStorage.get(context, key(KEY_MIGRATED, addr), "false")?.toBoolean() ?: false

        fun markMigrated(context: Context, addr: String) {
            LocalDataStorage.put(context, key(KEY_MIGRATED, addr), "true")
        }

        private fun serializeDays(days: Set<DayOfWeek>): String =
            days.map { it.value }.sorted().joinToString(",")

        private fun parseDays(raw: String): Set<DayOfWeek> =
            raw.split(",").mapNotNull { it.toIntOrNull()?.let { v -> DayOfWeek.of(v) } }.toSet()

        internal fun serializeDaysForTest(days: Set<DayOfWeek>): String = serializeDays(days)
        internal fun parseDaysForTest(raw: String): Set<DayOfWeek> = parseDays(raw)
    }
}
