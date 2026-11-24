package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.AutoSyncSchedule

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
        val end = if (state.endHour == 0) 24 else state.endHour
        "%02d-%02d".format(
            AutoSyncSchedule.shiftedHour(state.startHour, state.syncShift),
            AutoSyncSchedule.shiftedHour(end, state.syncShift),
        )
    } else {
        "Off"
    }

internal fun showHourlySignalAutoSyncHint(state: HourlySignalUiState): Boolean =
    state.enabled &&
        !(state.startHour == 1 &&
            state.endHour == 0 &&
            state.days.containsAll(DayOfWeek.entries) &&
            state.days.size == DayOfWeek.entries.size)

/** First and last chime hour (1..24) the watch really sounds for a stored window. */
internal fun shownWindow(startHour: Int, endHour: Int, shift: Int): Pair<Int, Int> =
    AutoSyncSchedule.shiftedHour(startHour, shift) to AutoSyncSchedule.shiftedHour(if (endHour == 0) 24 else endHour, shift)

/** The sync times that start and end the four segments, for the slider ticks (5 labels). */
internal fun signalSegmentEdges(shift: Int, minute: Int): List<String> =
    List(5) { k -> "%02d:%02d".format(Math.floorMod(6 * k + shift, 24), minute.coerceIn(0, 59)) }

internal fun chimeCount(startHour: Int, endHour: Int): Int {
    val end = if (endHour == 0) 24 else endHour
    return (end - startHour).coerceAtLeast(0)
}
