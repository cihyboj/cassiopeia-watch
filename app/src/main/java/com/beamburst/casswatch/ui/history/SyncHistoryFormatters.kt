package com.beamburst.casswatch.ui.history

import com.beamburst.casswatch.data.local.SyncTrigger

enum class SyncHistoryTone {
    SUCCESS,
    WARNING,
    ERROR,
    INFO,
    NEUTRAL,
}

internal fun syncHistoryTag(trigger: SyncTrigger): String =
    when (trigger) {
        SyncTrigger.AUTO -> "AUTO  "
        SyncTrigger.AUTO_ON_RECONNECT -> "RECON "
        SyncTrigger.WATCH_SHORT_PRESS -> "SHORT "
        SyncTrigger.WATCH_LONG_PRESS -> "LONG  "
        SyncTrigger.WATCH_NORMAL_CONNECTION -> "NCONN "
        SyncTrigger.WATCH_ALWAYS_CONNECTED -> "ACONN "
        SyncTrigger.CONNECTION_CONNECTED -> "CONN  "
        SyncTrigger.CONNECTION_DISCONNECTED -> "DISC  "
        SyncTrigger.CONNECTION_FAILED -> "FAIL  "
        else -> "MANUAL"
    }

internal fun syncHistoryTone(trigger: SyncTrigger, success: Boolean = true): SyncHistoryTone =
    if (!success) {
        SyncHistoryTone.ERROR
    } else {
        when (trigger) {
            SyncTrigger.CONNECTION_CONNECTED -> SyncHistoryTone.SUCCESS
            SyncTrigger.AUTO_ON_RECONNECT -> SyncHistoryTone.WARNING
            SyncTrigger.CONNECTION_DISCONNECTED,
            SyncTrigger.CONNECTION_FAILED -> SyncHistoryTone.ERROR
            SyncTrigger.AUTO,
            SyncTrigger.WATCH_SHORT_PRESS,
            SyncTrigger.WATCH_LONG_PRESS,
            SyncTrigger.WATCH_NORMAL_CONNECTION,
            SyncTrigger.WATCH_ALWAYS_CONNECTED -> SyncHistoryTone.INFO
            SyncTrigger.MANUAL_SET_TIME,
            SyncTrigger.MANUAL_SEND_ALARMS,
            SyncTrigger.MANUAL_SEND_EVENTS,
            SyncTrigger.MANUAL_SEND_SETTINGS,
            SyncTrigger.MANUAL_SETTINGS_SET_TIME -> SyncHistoryTone.NEUTRAL
        }
    }

internal fun syncHistoryDescription(trigger: SyncTrigger): String =
    when (trigger) {
        SyncTrigger.AUTO -> "Automatic sync"
        SyncTrigger.AUTO_ON_RECONNECT -> "Automatic sync on reconnect"
        SyncTrigger.WATCH_SHORT_PRESS -> "Short button press"
        SyncTrigger.WATCH_LONG_PRESS -> "Long button press / Find Phone"
        SyncTrigger.WATCH_NORMAL_CONNECTION -> "Normal connection button"
        SyncTrigger.WATCH_ALWAYS_CONNECTED -> "Always-connected button"
        SyncTrigger.MANUAL_SET_TIME -> "Manual time sync"
        SyncTrigger.MANUAL_SEND_ALARMS -> "Manual alarm sync"
        SyncTrigger.MANUAL_SEND_EVENTS -> "Manual event sync"
        SyncTrigger.MANUAL_SEND_SETTINGS -> "Manual settings sync"
        SyncTrigger.MANUAL_SETTINGS_SET_TIME -> "Settings set-time action"
        SyncTrigger.CONNECTION_CONNECTED -> "Connection setup complete"
        SyncTrigger.CONNECTION_DISCONNECTED -> "Disconnected"
        SyncTrigger.CONNECTION_FAILED -> "Connection failed"
    }

internal fun formatAlarmSyncState(raw: String): String {
    val parts = raw.split(':')
    if (parts.size < 3) return raw

    val time = "${parts[0]}:${parts[1]}"
    val enabled = parts.last().equals("true", ignoreCase = true)
    val symbol = if (enabled) "●" else "○"
    return "$time$symbol"
}

internal fun formatAlarmSyncStates(rawStates: List<String>): String =
    rawStates.joinToString(", ") { formatAlarmSyncState(it) }

internal fun formatHourlySignalTarget(target: Boolean?): String =
    if (target == true) "ON" else "OFF"
