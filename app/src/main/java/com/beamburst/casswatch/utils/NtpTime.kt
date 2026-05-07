package com.beamburst.casswatch.utils

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WatchTime(
    val timeMs: Long,
    val sourceLabel: String,
    val fallbackReason: String? = null,
)

fun WatchTime.syncHistoryActions(primaryAction: String): List<String> =
    listOfNotNull(
        primaryAction,
        "Time source: $sourceLabel",
        fallbackReason,
    )

object NtpTimeSettings {
    const val DEFAULT_SERVER = "pool.ntp.org"

    fun normalizeServer(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DEFAULT_SERVER
        if (trimmed.length > 253) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        if (trimmed.any { it == '/' || it == '\\' }) return null
        return trimmed
    }
}

@Singleton
class WatchTimeProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sntpClient: SntpClient,
) {
    suspend fun nowForWatch(): WatchTime {
        val fineAdjustment = LocalDataStorage.getFineTimeAdjustment(context)
        if (!LocalDataStorage.getNtpTimeEnabled(context)) {
            return WatchTime(
                timeMs = System.currentTimeMillis() + fineAdjustment,
                sourceLabel = "Phone time",
            )
        }

        val server = LocalDataStorage.getNtpTimeServer(context)
        return runCatching {
            val ntpTime = sntpClient.currentTimeMillis(server)
            WatchTime(
                timeMs = ntpTime + fineAdjustment,
                sourceLabel = "NTP $server",
            )
        }.getOrElse { error ->
            WatchTime(
                timeMs = System.currentTimeMillis() + fineAdjustment,
                sourceLabel = "Phone time",
                fallbackReason = "NTP failed: ${error.message ?: error::class.java.simpleName}; using phone time",
            )
        }
    }
}

@Singleton
class SntpClient @Inject constructor() {
    suspend fun currentTimeMillis(
        server: String,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Long = withContext(Dispatchers.IO) {
        query(server, timeoutMillis).timeMs
    }

    private fun query(server: String, timeoutMillis: Int): SntpResult {
        val address = InetAddress.getByName(server)
        val buffer = ByteArray(NTP_PACKET_SIZE)
        buffer[0] = (NTP_MODE_CLIENT or NTP_VERSION).toByte()

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            val requestTime = System.currentTimeMillis()
            writeTimestamp(buffer, TRANSMIT_TIME_OFFSET, requestTime)
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))

            val response = DatagramPacket(ByteArray(NTP_PACKET_SIZE), NTP_PACKET_SIZE)
            socket.receive(response)
            val responseTime = System.currentTimeMillis()
            val data = response.data
            val receiveTime = readTimestamp(data, RECEIVE_TIME_OFFSET)
            val transmitTime = readTimestamp(data, TRANSMIT_TIME_OFFSET)
            val offset = calculateOffset(
                requestTime = requestTime,
                receiveTime = receiveTime,
                transmitTime = transmitTime,
                responseTime = responseTime,
            )
            return SntpResult(timeMs = responseTime + offset, offsetMs = offset)
        }
    }

    companion object {
        private const val NTP_PORT = 123
        private const val NTP_PACKET_SIZE = 48
        private const val DEFAULT_TIMEOUT_MILLIS = 3_000
        private const val NTP_MODE_CLIENT = 3
        private const val NTP_VERSION = 4 shl 3
        private const val RECEIVE_TIME_OFFSET = 32
        private const val TRANSMIT_TIME_OFFSET = 40
        private const val NTP_TO_UNIX_EPOCH_SECONDS = 2_208_988_800L

        internal fun calculateOffset(
            requestTime: Long,
            receiveTime: Long,
            transmitTime: Long,
            responseTime: Long,
        ): Long =
            ((receiveTime - requestTime) + (transmitTime - responseTime)) / 2

        internal fun readTimestamp(buffer: ByteArray, offset: Int): Long {
            val seconds = read32(buffer, offset) - NTP_TO_UNIX_EPOCH_SECONDS
            val fraction = read32(buffer, offset + 4)
            return seconds * 1_000L + ((fraction * 1_000L) + FRACTION_ROUNDING) / FRACTION_SCALE
        }

        internal fun writeTimestamp(buffer: ByteArray, offset: Int, timeMs: Long) {
            val seconds = timeMs / 1_000L + NTP_TO_UNIX_EPOCH_SECONDS
            val milliseconds = timeMs % 1_000L
            val fraction = ((milliseconds * FRACTION_SCALE) + 500L) / 1_000L
            write32(buffer, offset, seconds)
            write32(buffer, offset + 4, fraction)
        }

        private const val FRACTION_SCALE = 0x1_0000_0000L
        private const val FRACTION_ROUNDING = FRACTION_SCALE / 2L

        private fun read32(buffer: ByteArray, offset: Int): Long =
            ((buffer[offset].toLong() and 0xffL) shl 24) or
                ((buffer[offset + 1].toLong() and 0xffL) shl 16) or
                ((buffer[offset + 2].toLong() and 0xffL) shl 8) or
                (buffer[offset + 3].toLong() and 0xffL)

        private fun write32(buffer: ByteArray, offset: Int, value: Long) {
            buffer[offset] = (value shr 24).toByte()
            buffer[offset + 1] = (value shr 16).toByte()
            buffer[offset + 2] = (value shr 8).toByte()
            buffer[offset + 3] = value.toByte()
        }
    }
}

data class SntpResult(
    val timeMs: Long,
    val offsetMs: Long,
)
