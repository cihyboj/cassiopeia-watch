package com.beamburst.casswatch.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtpTimeTest {
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
}
