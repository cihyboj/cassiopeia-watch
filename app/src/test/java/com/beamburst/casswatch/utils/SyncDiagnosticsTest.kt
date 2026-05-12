package com.beamburst.casswatch.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDiagnosticsTest {

    private fun base(
        encodedTimeMs: Long = 1_700_000_000_000L,
        preSendWallMs: Long = 1_700_000_000_000L,
        ntpOffsetMs: Long? = null,
        ntpRttMs: Long? = null,
        ntpAgeMs: Long? = null,
        durationMs: Long = 12L,
        cacheWarmAtWriteStart: Boolean = false,
    ) = SyncDiagnostics(
        encodedTimeMs, preSendWallMs, ntpOffsetMs, ntpRttMs, ntpAgeMs, durationMs, cacheWarmAtWriteStart,
    )

    @Test
    fun `phone-wall delta is positive when encoded is ahead`() {
        val lines = base(encodedTimeMs = 1_700_000_000_500L, preSendWallMs = 1_700_000_000_000L).formatLines()
        assertTrue(lines.any { it.contains("Δ vs phone wall = +500ms") })
    }

    @Test
    fun `phone-wall delta is negative when encoded is behind`() {
        val lines = base(encodedTimeMs = 1_700_000_000_000L, preSendWallMs = 1_700_000_000_500L).formatLines()
        assertTrue(lines.any { it.contains("Δ vs phone wall = -500ms") })
    }

    @Test
    fun `NTP delta omitted when no NTP offset`() {
        val deltaLine = base().formatLines()[1]
        assertTrue(deltaLine.contains("Δ vs phone wall"))
        assertNull(Regex("Δ vs NTP").find(deltaLine)?.value?.let { null })
        assertTrue(!deltaLine.contains("Δ vs NTP"))
    }

    @Test
    fun `NTP delta is rendered when offset is present`() {
        val lines = base(
            encodedTimeMs = 1_700_000_000_000L,
            preSendWallMs = 1_700_000_000_000L,
            ntpOffsetMs = 1_000L,
            ntpRttMs = 25L,
            ntpAgeMs = 15_000L,
        ).formatLines()
        assertTrue(lines.any { it.contains("Δ vs NTP = -1000ms") })
        assertTrue(lines.any { it.contains("NTP age=15s") && it.contains("rtt=25 ms") && it.contains("offset=+1000ms") })
    }

    @Test
    fun `formats BLE round-trip and cache warm flag`() {
        val secondary = base(durationMs = 42L, cacheWarmAtWriteStart = true).formatLines()[2]
        assertTrue(secondary.contains("BLE round-trip = 42 ms"))
        assertTrue(secondary.contains("cache warm at start=true"))
    }

    @Test
    fun `encoded line includes ISO timestamp`() {
        val lines = base(encodedTimeMs = 0L).formatLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("encoded → set: "))
    }
}
