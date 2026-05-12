package com.beamburst.casswatch.data.local

import org.junit.Assert.assertTrue
import org.junit.Test

class PerWatchCacheCleanerTest {

    @Test
    fun `wipeKeys includes all alarm keys for address`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        val keys = PerWatchCacheCleaner.wipeKeys(addr)
        assertTrue("PhoneAlarmDrafts_$addr" in keys)
        assertTrue("PhoneAlarmDraftsDirty_$addr" in keys)
        assertTrue("AlarmViewMode_$addr" in keys)
        assertTrue("AlarmDaySelections_$addr" in keys)
    }

    @Test
    fun `wipeKeys includes hourly signal keys`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        val keys = PerWatchCacheCleaner.wipeKeys(addr)
        assertTrue("HourlySignalEnabled_$addr" in keys)
        assertTrue("HourlySignalMigrated_$addr" in keys)
    }

    @Test
    fun `wipeKeys includes sync history and snapshot keys`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        val keys = PerWatchCacheCleaner.wipeKeys(addr)
        assertTrue("SyncHistory_$addr" in keys)
        assertTrue("WatchSnapshot_$addr" in keys)
        assertTrue("AutoSyncOnReconnect_$addr" in keys)
    }

    @Test
    fun `wipeKeys includes watch settings keys`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        val keys = PerWatchCacheCleaner.wipeKeys(addr)
        assertTrue("WatchSettings_$addr" in keys)
        assertTrue("WatchSettings_${addr}_dirty" in keys)
    }

    @Test
    fun `wipe does not include keys for other address`() {
        val addr = "AA:BB:CC:DD:EE:FF"
        val otherAddr = "11:22:33:44:55:66"
        val keys = PerWatchCacheCleaner.wipeKeys(addr)
        assertTrue(keys.none { it.endsWith("_$otherAddr") })
    }
}
