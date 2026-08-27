package com.beamburst.casswatch.services

import org.avmedia.gshockapi.NotificationType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class NotificationTimestampTest {

    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun info(timestamp: Long) = NotificationInfo(
        packageName = "com.example",
        appName = "Example",
        title = "title",
        text = "text",
        shortText = "short",
        type = NotificationType.GENERIC,
        timestamp = timestamp,
    )

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    @Test
    fun `timestamp fields are zero padded`() {
        val notification = NotificationMonitorService.createAppNotification(
            info(millisOf(2026, 1, 5, 8, 9, 7))
        )
        assertEquals("20260105T080907", notification.timestamp)
    }

    @Test
    fun `seconds under ten are not space padded`() {
        val notification = NotificationMonitorService.createAppNotification(
            info(millisOf(2026, 12, 31, 23, 59, 3))
        )
        assertEquals("20261231T235903", notification.timestamp)
    }
}
