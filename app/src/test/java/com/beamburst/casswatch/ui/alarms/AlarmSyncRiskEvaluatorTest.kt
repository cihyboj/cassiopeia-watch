package com.beamburst.casswatch.ui.alarms

import org.avmedia.gshockapi.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class AlarmSyncRiskEvaluatorTest {
    @Test
    fun `warns when dirty alarm occurs before next auto sync`() {
        val risk = AlarmSyncRiskEvaluator.evaluate(
            alarm = Alarm(12, 10, true, false, "Lunch"),
            alarmIndex = 0,
            alarmDays = setOf(DayOfWeek.WEDNESDAY),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = emptyMap(),
            isDirty = true,
            syncedHashes = emptyList(),
            now = LocalDateTime.of(2026, 5, 6, 10, 0),
            autoSyncEnabled = true,
            adjustmentTimeMinutes = 30,
        )

        assertNotNull(risk)
        assertEquals(LocalDateTime.of(2026, 5, 6, 12, 10), risk?.nextAlarmAt)
        assertEquals(LocalDateTime.of(2026, 5, 6, 12, 30), risk?.nextAutoSyncAt)
    }

    @Test
    fun `warns when dirty alarm is at same time as next auto sync`() {
        val risk = AlarmSyncRiskEvaluator.evaluate(
            alarm = Alarm(12, 30, true, false, "Lunch"),
            alarmIndex = 0,
            alarmDays = setOf(DayOfWeek.WEDNESDAY),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = emptyMap(),
            isDirty = true,
            syncedHashes = emptyList(),
            now = LocalDateTime.of(2026, 5, 6, 10, 0),
            autoSyncEnabled = true,
            adjustmentTimeMinutes = 30,
        )

        assertNotNull(risk)
        assertEquals(LocalDateTime.of(2026, 5, 6, 12, 30), risk?.nextAlarmAt)
        assertEquals(LocalDateTime.of(2026, 5, 6, 12, 30), risk?.nextAutoSyncAt)
    }

    @Test
    fun `does not warn when next auto sync is before alarm`() {
        val risk = AlarmSyncRiskEvaluator.evaluate(
            alarm = Alarm(13, 0, true, false, "Lunch"),
            alarmIndex = 0,
            alarmDays = setOf(DayOfWeek.WEDNESDAY),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = emptyMap(),
            isDirty = true,
            syncedHashes = emptyList(),
            now = LocalDateTime.of(2026, 5, 6, 10, 0),
            autoSyncEnabled = true,
            adjustmentTimeMinutes = 30,
        )

        assertNull(risk)
    }

    @Test
    fun `does not warn when alarm payload was already confirmed by sync history`() {
        val risk = AlarmSyncRiskEvaluator.evaluate(
            alarm = Alarm(12, 10, true, false, "Lunch"),
            alarmIndex = 0,
            alarmDays = setOf(DayOfWeek.WEDNESDAY),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = emptyMap(),
            isDirty = true,
            syncedHashes = listOf("12:10:true"),
            now = LocalDateTime.of(2026, 5, 6, 10, 0),
            autoSyncEnabled = true,
            adjustmentTimeMinutes = 30,
        )

        assertNull(risk)
    }

    @Test
    fun `warns when automatic sync is disabled`() {
        val risk = AlarmSyncRiskEvaluator.evaluate(
            alarm = Alarm(12, 10, true, false, "Lunch"),
            alarmIndex = 0,
            alarmDays = setOf(DayOfWeek.WEDNESDAY),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = emptyMap(),
            isDirty = true,
            syncedHashes = emptyList(),
            now = LocalDateTime.of(2026, 5, 6, 10, 0),
            autoSyncEnabled = false,
            adjustmentTimeMinutes = 30,
        )

        assertNotNull(risk)
        assertEquals(LocalDateTime.of(2026, 5, 6, 12, 10), risk?.nextAlarmAt)
        assertNull(risk?.nextAutoSyncAt)
    }
}
