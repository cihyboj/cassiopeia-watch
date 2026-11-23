package com.beamburst.casswatch.steps

import org.avmedia.gshockapi.model.StepCounterData
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Where a day's total came from. */
enum class DaySource {
    /** Running total read during that day; grows until midnight. */
    TODAY,

    /** The watch's own summary of a finished day. */
    SUMMARY,
}

data class DaySteps(
    val steps: Int,
    val distanceMeters: Int?,
    val source: DaySource,
    val fetchedAtMs: Long,
)

/**
 * Step history kept on the phone for one watch. The watch only holds today, ~one day of
 * hourly records and 7 daily summaries, so every read is merged into this history.
 *
 * [hourly] is the 24 hour slots of the latest reading's day ([hourlyDate]). Readings of the
 * same day are merged slot by slot: once a read closes the watch's transaction, the next one
 * returns only the newer records, so a missing slot never erases a known one.
 */
data class StepHistory(
    val days: Map<LocalDate, DaySteps> = emptyMap(),
    val hourlyDate: LocalDate? = null,
    val hourly: List<Int?> = emptyList(),
    val goal: Int? = null,
    val goalReadOn: LocalDate? = null,
    val watchTimestamp: LocalDateTime? = null,
    val fetchedAtMs: Long? = null,
)

object StepHistoryMerge {
    const val MAX_DAYS = 400L

    /**
     * `dailyHistory[i]` is taken as the day `i + DAILY_OFFSET` before the watch's date
     * (index 0 = yesterday). Not yet verified on hardware: check it against the watch's
     * own history screen after a day has closed.
     */
    const val DAILY_OFFSET = 1

    private const val CACHED_PREFIX = "cached"

    /** A fresh, decoded reading. Cached repeats and "unavailable" records are not new data. */
    fun isUsable(data: StepCounterData): Boolean =
        data.timestamp != null &&
            data.currentDaySteps != null &&
            data.warnings.none { it.startsWith(CACHED_PREFIX) }

    fun merge(history: StepHistory, data: StepCounterData, fetchedAtMs: Long): StepHistory {
        if (!isUsable(data)) return history
        val timestamp = data.timestamp ?: return history
        val today = timestamp.toLocalDate()
        val days = history.days.toMutableMap()

        todaySteps(data)?.let { steps ->
            put(days, today, DaySteps(steps, data.distanceMeters, DaySource.TODAY, fetchedAtMs))
        }
        data.dailyHistory.forEachIndexed { index, steps ->
            if (steps == null) return@forEachIndexed
            val date = today.minusDays((index + DAILY_OFFSET).toLong())
            put(days, date, DaySteps(steps, data.dailyDistances.getOrNull(index), DaySource.SUMMARY, fetchedAtMs))
        }
        val cutoff = today.minusDays(MAX_DAYS)
        days.keys.removeAll { it < cutoff }

        val newer = history.watchTimestamp == null || !timestamp.isBefore(history.watchTimestamp)
        val readHourly = hourlyUpTo(data.hourlyByHour, timestamp.hour)
        val (hourlyDate, hourly) = when {
            history.hourlyDate == today -> today to mergeHourly(history.hourly, readHourly)
            newer -> today to readHourly
            else -> history.hourlyDate to history.hourly
        }
        return history.copy(
            days = days,
            hourlyDate = hourlyDate,
            hourly = hourly,
            watchTimestamp = if (newer) timestamp else history.watchTimestamp,
            fetchedAtMs = fetchedAtMs,
        )
    }

    /**
     * Today's count as the watch displays it. The record holds two totals: a binary one
     * (equal to the sum of the hourly records) and a BCD one, which is what the watch face
     * shows; on the ABL-100WE the BCD one was 2 lower in both readings checked on 2026-11-23
     * (475/477, 1457/1459). An all-zero or larger BCD value is not trusted.
     */
    fun todaySteps(data: StepCounterData): Int? {
        val binary = data.currentDaySteps ?: return null
        return data.bcdTotalSteps?.takeIf { it in 1..binary } ?: binary
    }

    fun withGoal(history: StepHistory, goal: Int, readOn: LocalDate): StepHistory =
        history.copy(goal = goal, goalReadOn = readOn)

    /** An hour's count only grows; a slot the watch no longer returns keeps its known value. */
    private fun mergeHourly(old: List<Int?>, new: List<Int?>): List<Int?> =
        List(HOURS) { h ->
            val a = old.getOrNull(h)
            val b = new.getOrNull(h)
            if (a == null || b == null) a ?: b else maxOf(a, b)
        }

    /** Slots after the reading's hour can only be wrapped-around yesterday data. */
    private fun hourlyUpTo(byHour: List<Int?>, hour: Int): List<Int?> =
        List(HOURS) { h -> if (h <= hour) byHour.getOrNull(h) else null }

    private fun put(days: MutableMap<LocalDate, DaySteps>, date: LocalDate, new: DaySteps) {
        val old = days[date]
        // A day's count only grows: an older running total, or a record the watch returns
        // emptied after its transaction was closed, must never lower a known value.
        days[date] = when {
            old == null -> new
            new.steps > old.steps -> new
            new.steps < old.steps -> old
            // Same count: a finished day's summary is the better label.
            new.source == DaySource.SUMMARY -> new
            else -> old
        }
    }

    const val HOURS = 24
}

internal object StepHistoryJson {
    private const val VERSION = 1

    fun encode(history: StepHistory): String = JSONObject().apply {
        put("v", VERSION)
        put("days", JSONArray().apply {
            history.days.toSortedMap().forEach { (date, day) ->
                put(JSONObject().apply {
                    put("d", date.toString())
                    put("s", day.steps)
                    day.distanceMeters?.let { put("m", it) }
                    put("src", day.source.name)
                    put("at", day.fetchedAtMs)
                })
            }
        })
        history.hourlyDate?.let { put("hDate", it.toString()) }
        put("hourly", JSONArray().apply { history.hourly.forEach { put(it ?: JSONObject.NULL) } })
        history.goal?.let { put("goal", it) }
        history.goalReadOn?.let { put("goalOn", it.toString()) }
        history.watchTimestamp?.let { put("watchTs", it.toString()) }
        history.fetchedAtMs?.let { put("fetchedAt", it) }
    }.toString()

    fun decode(raw: String): StepHistory = runCatching {
        val json = JSONObject(raw)
        val days = buildMap {
            val arr = json.optJSONArray("days") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val source = runCatching { DaySource.valueOf(o.getString("src")) }.getOrNull() ?: continue
                put(
                    LocalDate.parse(o.getString("d")),
                    DaySteps(
                        steps = o.getInt("s"),
                        distanceMeters = if (o.has("m")) o.getInt("m") else null,
                        source = source,
                        fetchedAtMs = o.optLong("at"),
                    ),
                )
            }
        }
        val hourlyArr = json.optJSONArray("hourly") ?: JSONArray()
        StepHistory(
            days = days,
            hourlyDate = json.optString("hDate").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
            hourly = List(hourlyArr.length()) { i -> if (hourlyArr.isNull(i)) null else hourlyArr.getInt(i) },
            goal = if (json.has("goal")) json.getInt("goal") else null,
            goalReadOn = json.optString("goalOn").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
            watchTimestamp = json.optString("watchTs").takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse),
            fetchedAtMs = if (json.has("fetchedAt")) json.getLong("fetchedAt") else null,
        )
    }.getOrDefault(StepHistory())
}
