package com.beamburst.casswatch.steps

import com.beamburst.casswatch.ui.time.stepsChartModel
import org.avmedia.gshockapi.model.StepCounterData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StepHistoryMergeTest {
    private val nov21 = LocalDate.of(2026, 11, 21)

    private fun reading(
        at: LocalDateTime,
        today: Int?,
        daily: List<Int?> = emptyList(),
        byHour: List<Int?> = List(24) { null },
        warnings: List<String> = emptyList(),
    ) = StepCounterData(
        timestamp = at,
        hourlySteps = emptyList(),
        dailyHistory = daily,
        dailyDistances = daily.map { it?.let { s -> s / 2 } },
        currentDaySteps = today,
        distanceMeters = today?.let { it / 2 },
        hourlyByHour = byHour,
        warnings = warnings,
    )

    @Test
    fun `today and the 7 daily summaries land on their dates`() {
        val h = StepHistoryMerge.merge(StepHistory(), reading(nov21.atTime(15, 7), 4000, listOf(9000, 8000, null)), 1L)
        assertEquals(4000, h.days[nov21]?.steps)
        assertEquals(DaySource.TODAY, h.days[nov21]?.source)
        assertEquals(9000, h.days[nov21.minusDays(1)]?.steps)
        assertEquals(8000, h.days[nov21.minusDays(2)]?.steps)
        assertNull("unknown is not zero", h.days[nov21.minusDays(3)])
    }

    @Test
    fun `a finished day's summary replaces the running total read that day`() {
        val first = StepHistoryMerge.merge(StepHistory(), reading(nov21.atTime(21, 7), 7000), 1L)
        val next = StepHistoryMerge.merge(first, reading(nov21.plusDays(1).atTime(3, 7), 100, listOf(7600)), 2L)
        assertEquals(7600, next.days[nov21]?.steps)
        assertEquals(DaySource.SUMMARY, next.days[nov21]?.source)
        // A later running total never overrides the summary.
        val again = StepHistoryMerge.merge(next, reading(nov21.atTime(23, 0), 7500), 3L)
        assertEquals(7600, again.days[nov21]?.steps)
    }

    @Test
    fun `an older running total does not lower today`() {
        val h1 = StepHistoryMerge.merge(StepHistory(), reading(nov21.atTime(15, 7), 5000), 2L)
        val h2 = StepHistoryMerge.merge(h1, reading(nov21.atTime(9, 7), 1200), 3L)
        assertEquals(5000, h2.days[nov21]?.steps)
        assertEquals(nov21.atTime(15, 7), h2.watchTimestamp)
    }

    @Test
    fun `cached repeats and unavailable records are ignored`() {
        val base = StepHistoryMerge.merge(StepHistory(), reading(nov21.atTime(15, 7), 5000), 2L)
        val cached = reading(nov21.atTime(15, 8), 9999, warnings = listOf("cached: peek transaction still open in this connection"))
        assertSame(base, StepHistoryMerge.merge(base, cached, 3L))
        assertSame(base, StepHistoryMerge.merge(base, StepCounterData.unavailable(), 3L))
    }

    @Test
    fun `hourly slots after the reading's hour are dropped`() {
        val byHour = List(24) { h -> if (h == 8 || h == 23) 100 else null }
        val h = StepHistoryMerge.merge(StepHistory(), reading(nov21.atTime(9, 7), 300, byHour = byHour), 1L)
        assertEquals(100, h.hourly[8])
        assertNull(h.hourly[23])
        assertEquals(nov21, h.hourlyDate)
    }

    @Test
    fun `days older than the limit are pruned`() {
        val old = StepHistory(days = mapOf(nov21.minusDays(500) to DaySteps(1, null, DaySource.SUMMARY, 0L)))
        val h = StepHistoryMerge.merge(old, reading(nov21.atTime(9, 0), 10), 1L)
        assertTrue(h.days.keys.all { it >= nov21.minusDays(StepHistoryMerge.MAX_DAYS) })
    }

    @Test
    fun `json round trip keeps everything`() {
        val h = StepHistoryMerge.withGoal(
            StepHistoryMerge.merge(StepHistory(), reading(nov21.atTime(15, 7), 4000, listOf(9000), List(24) { if (it == 14) 55 else null }), 42L),
            8000, nov21,
        )
        assertEquals(h, StepHistoryJson.decode(StepHistoryJson.encode(h)))
        assertEquals(StepHistory(), StepHistoryJson.decode("not json"))
    }

    @Test
    fun `chart shows the last 7 days and hides stale hourly data`() {
        val h = StepHistoryMerge.merge(StepHistory(), reading(nov21.atTime(15, 7), 4000, listOf(9000)), 1L)
        val sameDay = stepsChartModel(h, nov21)!!
        assertEquals(7, sameDay.week.size)
        assertEquals(nov21 to 4000, sameDay.week.last())
        assertEquals(24, sameDay.hourly.size)
        val nextDay = stepsChartModel(h, nov21.plusDays(1))!!
        assertNull(nextDay.todaySteps)
        assertTrue(nextDay.hourly.isEmpty())
        assertNull(stepsChartModel(StepHistory(), nov21))
    }
}
