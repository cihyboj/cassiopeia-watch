package com.beamburst.casswatch.data.local

import org.avmedia.gshockapi.WatchInfo
import java.time.LocalDateTime
import java.util.Locale

/**
 * The watch's own automatic sync: four connections a day, 6 hours apart, at the minute set in
 * byte 13 of the 0x11 packet. On the ABL-100WE the low bits of byte 12 move the cycle: hour 3
 * gives 03, 09, 15 and 21 (verified 2026-11-18 and 2026-11-21). Other models only expose
 * on/off, so their cycle stays at 00, 06, 12 and 18.
 */
object AutoSyncSchedule {
    private const val CYCLE_HOURS = 6
    private const val SYNCS_PER_DAY = 4

    /** Hours the first sync of the day can take; the cycle repeats every 6 hours. */
    val FIRST_HOURS = 0 until CYCLE_HOURS

    /** First sync hour of the day for any anchor (`-1` = unknown, treated as 0). */
    fun firstHour(anchorHour: Int): Int = anchorHour.coerceAtLeast(0) % CYCLE_HOURS

    /**
     * Only the ABL-100WE has been verified to honour the hour bits. [WatchInfo.model] is only
     * known while connected, so the saved watch name (e.g. "CASIO ABL-100WE") is checked too.
     */
    fun supportsHour(deviceName: String? = null): Boolean =
        WatchInfo.model == WatchInfo.WatchModel.ABL_100 || isAbl100Name(deviceName)

    internal fun isAbl100Name(deviceName: String?): Boolean =
        deviceName?.trim()?.split(" ")?.getOrNull(1)?.startsWith("ABL-100") == true

    /** Sync hours of the day for an anchor hour (`-1` = unknown, treated as 0), ascending. */
    fun hours(anchorHour: Int): List<Int> {
        val first = firstHour(anchorHour)
        return List(SYNCS_PER_DAY) { first + it * CYCLE_HOURS }
    }

    fun next(now: LocalDateTime, anchorHour: Int, minute: Int): LocalDateTime {
        val m = minute.coerceIn(0, 59)
        hours(anchorHour).forEach { hour ->
            val candidate = now.toLocalDate().atTime(hour, m)
            if (candidate.isAfter(now)) return candidate
        }
        return now.toLocalDate().plusDays(1).atTime(hours(anchorHour).first(), m)
    }

    /**
     * How far the hourly-signal segments move with the sync hour. The chime is switched only at
     * a sync, so a sync at H:MM covers the chimes H+1 … H+6. The stored window uses the segments
     * of an hour-0 cycle (01–06, 07–12, 13–18, 19–24); add this to show or apply them.
     */
    fun chimeShift(anchorHour: Int, deviceName: String?): Int =
        if (supportsHour(deviceName)) firstHour(anchorHour) else 0

    /** A window hour (1..24) of the hour-0 cycle moved by [shift], as 1..24. */
    fun shiftedHour(hour: Int, shift: Int): Int = Math.floorMod(hour - 1 + shift, 24) + 1

    fun format(anchorHour: Int, minute: Int): String =
        hours(anchorHour).joinToString(", ") { "%02d:%02d".format(Locale.ROOT, it, minute.coerceIn(0, 59)) }
}
