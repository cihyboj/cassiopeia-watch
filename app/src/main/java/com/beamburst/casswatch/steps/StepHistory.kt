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
 * [hourly] is the 24 hour slots of the latest reading's day ([hourlyDate]); it is replaced,
 * not accumulated, until the hourly record semantics are verified on hardware.
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

        data.currentDaySteps?.let { steps ->
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
        return history.copy(
            days = days,
            hourlyDate = if (newer) today else history.hourlyDate,
            hourly = if (newer) hourlyUpTo(data.hourlyByHour, timestamp.hour) else history.hourly,
            watchTimestamp = if (newer) timestamp else history.watchTimestamp,
            fetchedAtMs = fetchedAtMs,
        )
    }

    fun withGoal(history: StepHistory, goal: Int, readOn: LocalDate): StepHistory =
        history.copy(goal = goal, goalReadOn = readOn)

    /** Slots after the reading's hour can only be wrapped-around yesterday data. */
    private fun hourlyUpTo(byHour: List<Int?>, hour: Int): List<Int?> =
        List(HOURS) { h -> if (h <= hour) byHour.getOrNull(h) else null }

    private fun put(days: MutableMap<LocalDate, DaySteps>, date: LocalDate, new: DaySteps) {
        val old = days[date]
        days[date] = when {
            old == null -> new
            // A finished day's summary beats any running total read during that day.
            old.source == DaySource.SUMMARY && new.source == DaySource.TODAY -> old
            new.source == DaySource.SUMMARY && old.source == DaySource.TODAY -> new
            // A running total only grows within a day; an older read must not lower it.
            new.source == DaySource.TODAY -> if (new.steps >= old.steps) new else old
            new.fetchedAtMs >= old.fetchedAtMs -> new
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
