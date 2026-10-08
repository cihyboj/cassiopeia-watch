package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage

object MultiWatchMigrator {

    const val MIGRATED_FLAG_KEY = "MultiWatchMigrated"

    val GLOBAL_KEYS = listOf(
        "PhoneAlarmDrafts",
        "PhoneAlarmDraftsDirty",
        "AlarmViewMode",
        "AlarmDaySelections",
        "HourlySignalEnabled",
        "HourlySignalStartHour",
        "HourlySignalEndHour",
        "HourlySignalMigrated",
        "fineTimeAdjustment",
        "timeAdjustmentNotification",
    )

    fun perWatchKey(base: String, addr: String) = "${base}_$addr"

    /** Awaited by the alarm coordinator while holding its initialization mutex. */
    suspend fun migrateIfNeeded(context: Context) {
        if (LocalDataStorage.get(context, MIGRATED_FLAG_KEY, "false").toBoolean()) return
        val addr = LocalDataStorage.get(context, CurrentWatchSelector.LAST_DEVICE_ADDRESS_KEY)
        if (addr != null) {
            GLOBAL_KEYS.forEach { key ->
                LocalDataStorage.get(context, key)?.let { value ->
                    // Retry after a crash must never overwrite a newer per-watch draft.
                    val target = perWatchKey(key, addr)
                    if (LocalDataStorage.get(context, target) == null) LocalDataStorage.putAsync(context, target, value)
                    LocalDataStorage.deleteAsync(context, key)
                }
            }
        }
        LocalDataStorage.putAsync(context, MIGRATED_FLAG_KEY, "true")
    }
}
