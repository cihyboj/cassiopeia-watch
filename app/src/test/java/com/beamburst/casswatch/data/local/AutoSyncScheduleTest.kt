package com.beamburst.casswatch.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class AutoSyncScheduleTest {
    @Test
    fun `anchor hour shifts the four daily syncs`() {
        assertEquals(listOf(0, 6, 12, 18), AutoSyncSchedule.hours(0))
        assertEquals(listOf(3, 9, 15, 21), AutoSyncSchedule.hours(3))
        assertEquals(listOf(3, 9, 15, 21), AutoSyncSchedule.hours(15))
        assertEquals(listOf(0, 6, 12, 18), AutoSyncSchedule.hours(-1))
    }

    @Test
    fun `first hour of the day for any anchor`() {
        assertEquals(3, AutoSyncSchedule.firstHour(15))
        assertEquals(1, AutoSyncSchedule.firstHour(13))
        assertEquals(0, AutoSyncSchedule.firstHour(-1))
        assertEquals(0..5, AutoSyncSchedule.FIRST_HOURS)
    }

    @Test
    fun `formats the schedule as the watch sees it`() {
        assertEquals("03:07, 09:07, 15:07, 21:07", AutoSyncSchedule.format(3, 7))
    }

    @Test
    fun `next sync honours the anchor hour and wraps to tomorrow`() {
        val now = LocalDateTime.of(2026, 11, 21, 19, 30)
        assertEquals(LocalDateTime.of(2026, 11, 21, 21, 7), AutoSyncSchedule.next(now, 3, 7))
        assertEquals(LocalDateTime.of(2026, 11, 22, 3, 7), AutoSyncSchedule.next(now.withHour(22), 9, 7))
        assertEquals(LocalDateTime.of(2026, 11, 22, 0, 30), AutoSyncSchedule.next(now.withHour(23), 0, 30))
    }
}
