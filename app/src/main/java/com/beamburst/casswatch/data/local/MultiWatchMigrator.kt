package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    fun migrateIfNeeded(context: Context) {
        val alreadyMigrated = LocalDataStorage.get(context, MIGRATED_FLAG_KEY, "false")?.toBoolean() ?: false
        if (alreadyMigrated) return

        val addr = LocalDataStorage.get(context, CurrentWatchSelector.LAST_DEVICE_ADDRESS_KEY, null)
        CoroutineScope(Dispatchers.IO).launch {
            if (addr == null) {
                LocalDataStorage.putAsync(context, MIGRATED_FLAG_KEY, "true")
                return@launch
            }

            GLOBAL_KEYS.forEach { key ->
                LocalDataStorage.get(context, key, null)?.let { value ->
                    LocalDataStorage.putAsync(context, perWatchKey(key, addr), value)
                    LocalDataStorage.deleteAsync(context, key)
                }
            }
            LocalDataStorage.putAsync(context, MIGRATED_FLAG_KEY, "true")
        }
    }
}
