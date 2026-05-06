package com.beamburst.casswatch.ui.alarms

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

internal fun alarmRepeatLabel(
    selectedDays: Set<DayOfWeek>?,
    locale: Locale = Locale.getDefault(),
): String = when {
    selectedDays == null -> "Daily"
    selectedDays.isEmpty() -> "One-time alarm"
    selectedDays.containsAll(DayOfWeek.entries) && selectedDays.size == DayOfWeek.entries.size -> "Daily"
    else -> DayOfWeek.entries
        .filter { it in selectedDays }
        .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, locale) }
}

/** True when the repeating pattern is a partial week (shows day strip instead of alarmRepeatLabel text). */
internal fun useAlarmWeekdayStrip(selectedDays: Set<DayOfWeek>, showDayStrip: Boolean): Boolean =
    showDayStrip && selectedDays.size in 1 until DayOfWeek.entries.size

internal fun alarmTabSubtitle(alarms: List<org.avmedia.gshockapi.Alarm>): String {
    val enabledCount = alarms.count { it.enabled }
    return "$enabledCount ON"
}

internal fun timerTabSubtitle(totalSeconds: Int): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val seconds = safeSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

internal fun signalTabSubtitle(state: HourlySignalUiState): String =
    if (state.enabled) {
        "%02d-%02d".format(state.startHour, state.endHour)
    } else {
        "Off"
    }
