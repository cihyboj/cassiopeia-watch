package com.beamburst.casswatch.ui.history

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
