package com.beamburst.casswatch.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiWatchMigratorTest {

    @Test
    fun `GLOBAL_KEYS contains all alarm storage keys`() {
        val keys = MultiWatchMigrator.GLOBAL_KEYS
        assertTrue("PhoneAlarmDrafts" in keys)
        assertTrue("PhoneAlarmDraftsDirty" in keys)
        assertTrue("AlarmViewMode" in keys)
        assertTrue("AlarmDaySelections" in keys)
    }

    @Test
    fun `GLOBAL_KEYS contains all hourly signal keys`() {
        val keys = MultiWatchMigrator.GLOBAL_KEYS
        assertTrue("HourlySignalEnabled" in keys)
        assertTrue("HourlySignalStartHour" in keys)
        assertTrue("HourlySignalEndHour" in keys)
        assertTrue("HourlySignalMigrated" in keys)
    }

    @Test
    fun `GLOBAL_KEYS contains watch-settings keys`() {
        val keys = MultiWatchMigrator.GLOBAL_KEYS
        assertTrue("fineTimeAdjustment" in keys)
        assertTrue("timeAdjustmentNotification" in keys)
    }

    @Test
    fun `per-watch key naming convention`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        assertEquals(
            "PhoneAlarmDrafts_AA:BB:CC:DD:EE:FF",
            MultiWatchMigrator.perWatchKey("PhoneAlarmDrafts", addr)
        )
    }

    @Test
    fun `MIGRATED_FLAG_KEY is correct`() {
        assertEquals("MultiWatchMigrated", MultiWatchMigrator.MIGRATED_FLAG_KEY)
    }
}
