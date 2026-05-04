package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneFallbackReceiverHistoryTest {

    private fun syncEntry(alarmHashes: List<String>?, success: Boolean = true) = SyncEntry(
        timestamp = 1000L,
        deviceAddress = "AA:BB",
        trigger = SyncTrigger.MANUAL_SEND_ALARMS,
        kinds = setOf(SyncKind.ALARMS),
        success = success,
        errorMessage = null,
        details = SyncDetails(alarmHashes = alarmHashes, eventCount = null, hourlySignalTarget = null, timeMs = null),
    )

    private fun watchTrustsAlarm(entries: List<SyncEntry>, expectedHash: String): Boolean {
        val lastSuccess = entries.firstOrNull { it.success && SyncKind.ALARMS in it.kinds }
        return lastSuccess?.details?.alarmHashes?.contains(expectedHash) == true
    }

    @Test
    fun `trusts watch when expected hash in last successful alarm sync`() {
        val entries = listOf(syncEntry(listOf("06:30:true", "09:00:false")))
        assertTrue(watchTrustsAlarm(entries, "06:30:true"))
    }

    @Test
    fun `fires fallback when expected hash not in last successful sync`() {
        val entries = listOf(syncEntry(listOf("09:00:false")))
        assertFalse(watchTrustsAlarm(entries, "06:30:true"))
    }

    @Test
    fun `fires fallback when last alarm sync was a failure`() {
        val entries = listOf(syncEntry(listOf("06:30:true"), success = false))
        assertFalse(watchTrustsAlarm(entries, "06:30:true"))
    }

    @Test
    fun `fires fallback when history is empty`() {
        assertFalse(watchTrustsAlarm(emptyList(), "06:30:true"))
    }
}
