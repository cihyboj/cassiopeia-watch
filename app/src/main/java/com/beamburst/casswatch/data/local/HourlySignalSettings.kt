package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage

data class HourlySignalSettings(
    val enabled: Boolean,
    val startHour: Int,  // ∈ {1, 7, 13, 19}
    val endHour: Int,    // ∈ {6, 12, 18, 0}  (0 represents 24 / end-of-day)
) {
    companion object {
        val Default = HourlySignalSettings(enabled = false, startHour = 7, endHour = 18)

        private const val KEY_ENABLED  = "HourlySignalEnabled"
        private const val KEY_START    = "HourlySignalStartHour"
        private const val KEY_END      = "HourlySignalEndHour"
        private const val KEY_MIGRATED = "HourlySignalMigrated"

        fun load(context: Context): HourlySignalSettings = HourlySignalSettings(
            enabled   = LocalDataStorage.get(context, KEY_ENABLED, "false")?.toBoolean() ?: false,
            startHour = LocalDataStorage.get(context, KEY_START,   "7")?.toIntOrNull()   ?: 7,
            endHour   = LocalDataStorage.get(context, KEY_END,     "18")?.toIntOrNull()  ?: 18,
        )

        fun save(context: Context, settings: HourlySignalSettings) {
            LocalDataStorage.put(context, KEY_ENABLED, settings.enabled.toString())
            LocalDataStorage.put(context, KEY_START,   settings.startHour.toString())
            LocalDataStorage.put(context, KEY_END,     settings.endHour.toString())
        }

        fun isMigrated(context: Context): Boolean =
            LocalDataStorage.get(context, KEY_MIGRATED, "false")?.toBoolean() ?: false

        fun markMigrated(context: Context) {
            LocalDataStorage.put(context, KEY_MIGRATED, "true")
        }
    }
}
