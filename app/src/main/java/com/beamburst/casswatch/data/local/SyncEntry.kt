package com.beamburst.casswatch.data.local

enum class SyncTrigger {
    AUTO,
    AUTO_ON_RECONNECT,
    WATCH_SHORT_PRESS,
    WATCH_LONG_PRESS,
    WATCH_NORMAL_CONNECTION,
    WATCH_ALWAYS_CONNECTED,
    MANUAL_SET_TIME,
    MANUAL_SEND_ALARMS,
    MANUAL_SEND_EVENTS,
    MANUAL_SEND_SETTINGS,
    MANUAL_SETTINGS_SET_TIME,
    CONNECTION_CONNECTED,
    CONNECTION_DISCONNECTED,
    CONNECTION_FAILED,
}

enum class SyncKind { TIME, ALARMS, EVENTS, HOURLY_SIGNAL, SETTINGS, CONNECTION, ACTION, STEPS }

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
    val alarmHashes: List<String>?,
    val eventCount: Int?,
    val hourlySignalTarget: Boolean?,
    val timeMs: Long?,
    val connection: String? = null,
    val actions: List<String>? = null,
) {
    companion object {
        val Empty = SyncDetails(null, null, null, null)
    }
}
