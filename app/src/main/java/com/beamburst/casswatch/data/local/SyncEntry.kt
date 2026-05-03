package com.beamburst.casswatch.data.local

enum class SyncTrigger {
    AUTO,
    AUTO_ON_RECONNECT,
    MANUAL_SET_TIME,
    MANUAL_SEND_ALARMS,
    MANUAL_SEND_EVENTS,
    MANUAL_SETTINGS_SET_TIME,
}

enum class SyncKind { TIME, ALARMS, EVENTS, HOURLY_SIGNAL }

data class SyncEntry(
    val timestamp: Long,
    val deviceAddress: String,
    val trigger: SyncTrigger,
    val kinds: Set<SyncKind>,
    val success: Boolean,
    val errorMessage: String?,
    val details: SyncDetails,
)

data class SyncDetails(
    val alarmHashes: Set<String>?,
    val eventCount: Int?,
    val hourlySignalTarget: Boolean?,
    val timeMs: Long?,
) {
    companion object {
        val Empty = SyncDetails(null, null, null, null)
    }
}
