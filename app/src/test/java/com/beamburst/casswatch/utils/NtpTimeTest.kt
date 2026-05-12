package com.beamburst.casswatch.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtpTimeTest {
    private val nowMs = 1_800_000_000_000L

    @Test
    fun `encodes and decodes ntp timestamp`() {
        val packet = ByteArray(48)
        val fixedTime = 1_713_456_789_123L

        SntpClient.writeTimestamp(packet, 40, fixedTime)

        assertEquals(fixedTime, SntpClient.readTimestamp(packet, 40))
    }

    @Test
    fun `calculates clock offset from fixed packet times`() {
        val offset = SntpClient.calculateOffset(
            requestTime = 1_000L,
            receiveTime = 1_080L,
            transmitTime = 1_120L,
            responseTime = 1_200L,
        )

        assertEquals(0L, offset)
    }

    @Test
    fun `calculates positive clock offset`() {
        val offset = SntpClient.calculateOffset(
            requestTime = 1_000L,
            receiveTime = 1_180L,
            transmitTime = 1_220L,
            responseTime = 1_200L,
        )

        assertEquals(100L, offset)
    }

    @Test
    fun `normalizes ntp server`() {
        assertEquals(NtpTimeSettings.DEFAULT_SERVER, NtpTimeSettings.normalizeServer(""))
        assertEquals(NtpTimeSettings.DEFAULT_SERVER, NtpTimeSettings.normalizeServer("   "))
        assertEquals("time.example.com", NtpTimeSettings.normalizeServer(" time.example.com "))
    }

    @Test
    fun `rejects obvious invalid ntp server input`() {
        assertNull(NtpTimeSettings.normalizeServer("pool.ntp.org/path"))
        assertNull(NtpTimeSettings.normalizeServer("pool .ntp.org"))
        assertNull(NtpTimeSettings.normalizeServer("pool.ntp.org other"))
    }

    @Test
    fun `phone source with no adjustment lets api sample phone time`() {
        val timeMs = resolveWatchTimeMs(
            ntpCache = null,
            fineAdjustmentMs = 0L,
            latencyCompensationMs = 0L,
            wallNowMs = 10_000L,
            elapsedNowMs = 5_000L,
        )

        assertNull(timeMs)
    }

    @Test
    fun `phone source explicit timestamp adds compensation before fine adjustment`() {
        val timeMs = resolveWatchTimeMs(
            ntpCache = null,
            fineAdjustmentMs = -25L,
            latencyCompensationMs = 800L,
            wallNowMs = 10_000L,
            elapsedNowMs = 5_000L,
        )

        assertEquals(10_775L, timeMs)
    }

    @Test
    fun `ntp source computes from cached monotonic capture and adds adjustments`() {
        val cache = NtpOffsetCache(
            server = "time.example.com",
            offsetMs = 12L,
            roundTripMs = 34L,
            capturedElapsedRealtimeMs = 1_000L,
            ntpTimeAtCaptureMs = 20_000L,
        )

        val timeMs = resolveWatchTimeMs(
            ntpCache = cache,
            fineAdjustmentMs = 25L,
            latencyCompensationMs = 750L,
            wallNowMs = 99_000L,
            elapsedNowMs = 1_250L,
        )

        assertEquals(21_025L, timeMs)
    }

    @Test
    fun `ntp cache is valid for matching server within age window`() {
        val cache = NtpOffsetCache(
            server = "pool.ntp.org",
            offsetMs = 0L,
            roundTripMs = 30L,
            capturedElapsedRealtimeMs = 1_000L,
            ntpTimeAtCaptureMs = 50_000L,
        )

        assertEquals(true, cache.isFreshFor("pool.ntp.org", 1_000L + NTP_CACHE_VALID_MS))
    }

    @Test
    fun `ntp cache is stale after age window or reboot`() {
        val cache = NtpOffsetCache(
            server = "pool.ntp.org",
            offsetMs = 0L,
            roundTripMs = 30L,
            capturedElapsedRealtimeMs = 10_000L,
            ntpTimeAtCaptureMs = 50_000L,
        )

        assertEquals(false, cache.isFreshFor("pool.ntp.org", 10_001L + NTP_CACHE_VALID_MS))
        assertEquals(false, cache.isFreshFor("pool.ntp.org", 9_999L))
        assertEquals(false, cache.isFreshFor("time.example.com", 10_500L))
    }

    @Test
    fun `latency estimator uses median of valid samples`() {
        val compensation = learnedLatencyCompensationMs(listOf(80L, 400L, 120L, 200L, 160L))

        assertEquals(160L, compensation)
    }

    @Test
    fun `latency estimator ignores outliers and caps compensation`() {
        assertEquals(49L, learnedLatencyCompensationMs(listOf(1L, 49L, 5_001L)))
        // Mode-2-only samples produce sub-200 ms compensation; the cap prevents legacy
        // Mode-1 samples (or any large outliers within MAX_LATENCY_SAMPLE_MS) from leaking
        // into the estimate.
        assertEquals(MAX_LATENCY_COMPENSATION_MS, learnedLatencyCompensationMs(listOf(800L, 900L, 1_000L)))
    }

    @Test
    fun `latency estimator accepts low latency samples`() {
        assertEquals(3L, learnedLatencyCompensationMs(listOf(3L, 4L, 2L)))
    }

    @Test
    fun `latency parser supports legacy raw longs`() {
        val parsed = LocalDataStorage.parseLatencySamples("80,120,160", nowMs)

        assertEquals(listOf(80L, 120L, 160L), parsed.map { it.durationMs })
    }

    @Test
    fun `latency parser supports timestamped format`() {
        val parsed = LocalDataStorage.parseLatencySamples(
            "80@${nowMs - 1_000L},120@${nowMs - 2_000L}",
            nowMs,
        )

        assertEquals(listOf(80L, 120L), parsed.map { it.durationMs })
    }

    @Test
    fun `latency parser supports mixed legacy and timestamped format`() {
        val parsed = LocalDataStorage.parseLatencySamples("80,120@${nowMs - 2_000L},160", nowMs)

        assertEquals(listOf(80L, 120L, 160L), parsed.map { it.durationMs })
    }

    @Test
    fun `latency parser ignores stale samples older than 7 days`() {
        val stale = nowMs - LATENCY_SAMPLE_RETENTION_MS - 1L
        val parsed = LocalDataStorage.parseLatencySamples(
            "80@$stale,120@${nowMs - 2_000L}",
            nowMs,
        )

        assertEquals(listOf(120L), parsed.map { it.durationMs })
    }

    @Test
    fun `latency estimator returns zero when only stale data remains`() {
        val stale = nowMs - LATENCY_SAMPLE_RETENTION_MS - 10L
        val parsed = LocalDataStorage.parseLatencySamples("80@$stale,120@$stale", nowMs)
        val compensation = learnedLatencyCompensationMs(parsed.map { it.durationMs })

        assertEquals(0L, compensation)
    }

    @Test
    fun `warm path filter skips first sample for generation`() {
        val seen = mutableMapOf<String, Long>()
        val address = "AA:BB:CC:DD:EE:FF"

        assertEquals(false, shouldRecordLatencySample(seen, address, generation = 1L))
        assertEquals(true, shouldRecordLatencySample(seen, address, generation = 1L))
    }

    @Test
    fun `warm path filter skips first sample after generation bump`() {
        val seen = mutableMapOf<String, Long>()
        val address = "AA:BB:CC:DD:EE:FF"

        assertEquals(false, shouldRecordLatencySample(seen, address, generation = 1L))
        assertEquals(true, shouldRecordLatencySample(seen, address, generation = 1L))
        assertEquals(false, shouldRecordLatencySample(seen, address, generation = 2L))
        assertEquals(true, shouldRecordLatencySample(seen, address, generation = 2L))
    }

    /**
     * The second-boundary aim picks T = next whole-second value, then fires the BLE write
     * at T - estimateMs so that the packet lands at ~T. This test pins the math.
     */
    @Test
    fun `second boundary aim picks next whole second`() {
        // nowBefore = 1_730_000_000_123, target should be 1_730_000_001_000
        val nowBefore = 1_730_000_000_123L
        val target = ((nowBefore / 1000L) + 1L) * 1000L
        assertEquals(1_730_000_001_000L, target)
    }

    @Test
    fun `second boundary aim picks next second when already at the boundary`() {
        // At an exact boundary, we still aim for the *next* second to allow non-negative wait.
        val nowBefore = 1_730_000_000_000L
        val target = ((nowBefore / 1000L) + 1L) * 1000L
        assertEquals(1_730_000_001_000L, target)
    }

    @Test
    fun `second boundary wait is bounded by one second minus estimate`() {
        val nowBefore = 1_730_000_000_900L
        val target = ((nowBefore / 1000L) + 1L) * 1000L
        val estimateMs = 30L
        val fireAt = target - estimateMs
        val waitMs = fireAt - nowBefore
        // 1_730_000_001_000 - 30 - 1_730_000_000_900 = 70
        assertEquals(70L, waitMs)
        assertTrue("wait fits within a second", waitMs in 0L..(1_000L - 0L))
    }

    @Test
    fun `second boundary wait is negative when within estimate of next boundary`() {
        // If estimate > remaining ms until next boundary, we should not delay (caller skips
        // negative waits) and accept the slight pre-boundary delivery.
        val nowBefore = 1_730_000_000_990L
        val target = ((nowBefore / 1000L) + 1L) * 1000L
        val estimateMs = 30L
        val waitMs = (target - estimateMs) - nowBefore
        // 1_730_000_001_000 - 30 - 1_730_000_000_990 = -20
        assertTrue("wait is negative when too close", waitMs < 0L)
    }

    @Test
    fun `default send latency estimate is reasonable for Mode 2`() {
        // Document the assumption: a sane default before any samples land.
        assertTrue(DEFAULT_SEND_LATENCY_ESTIMATE_MS in 5L..100L)
    }
}
