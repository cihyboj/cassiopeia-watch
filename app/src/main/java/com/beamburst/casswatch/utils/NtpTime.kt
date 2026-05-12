package com.beamburst.casswatch.utils

import android.content.Context
import android.os.SystemClock
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.avmedia.gshockapi.IGShockAPI
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId

data class WatchTime(
    val ntpCache: NtpOffsetCache?,
    val fineAdjustmentMs: Long,
    val latencyCompensationMs: Long,
    val sourceLabel: String,
    val fallbackReason: String? = null,
) {
    fun resolveTimeMs(wallNowMs: Long, elapsedNowMs: Long): Long? =
        resolveWatchTimeMs(
            ntpCache = ntpCache,
            fineAdjustmentMs = fineAdjustmentMs,
            latencyCompensationMs = latencyCompensationMs,
            wallNowMs = wallNowMs,
            elapsedNowMs = elapsedNowMs,
        )

    fun ntpDiagnostic(elapsedNowMs: Long): String? =
        ntpCache?.let {
            "NTP offset: ${formatSignedMs(it.offsetMs)}, RTT: ${it.roundTripMs}ms, age: ${formatAge(elapsedNowMs - it.capturedElapsedRealtimeMs)}"
        }
}

data class TimeWriteResult(
    val timeMs: Long?,
    val durationMs: Long,
    val compensationMs: Long,
)

fun WatchTime.syncHistoryActions(
    primaryAction: String,
    writeResult: TimeWriteResult? = null,
): List<String> =
    listOfNotNull(
        primaryAction,
        "Time source: $sourceLabel",
        fallbackReason,
        ntpDiagnostic(SystemClock.elapsedRealtime()),
        writeResult?.let {
            "Time write duration: ${it.durationMs}ms; compensation: ${it.compensationMs}ms"
        },
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

data class NtpOffsetCache(
    val server: String,
    val offsetMs: Long,
    val roundTripMs: Long,
    val capturedElapsedRealtimeMs: Long,
    val ntpTimeAtCaptureMs: Long,
) {
    fun ntpNowMs(elapsedNowMs: Long): Long =
        ntpTimeAtCaptureMs + (elapsedNowMs - capturedElapsedRealtimeMs)

    fun isFreshFor(server: String, elapsedNowMs: Long, maxAgeMs: Long = NTP_CACHE_VALID_MS): Boolean =
        this.server == server &&
            elapsedNowMs >= capturedElapsedRealtimeMs &&
            elapsedNowMs - capturedElapsedRealtimeMs <= maxAgeMs
}

internal const val NTP_CACHE_VALID_MS = 30L * 60L * 1_000L

internal fun resolveWatchTimeMs(
    ntpCache: NtpOffsetCache?,
    fineAdjustmentMs: Long,
    latencyCompensationMs: Long,
    wallNowMs: Long,
    elapsedNowMs: Long,
): Long? {
    if (ntpCache != null) {
        return ntpCache.ntpNowMs(elapsedNowMs) + latencyCompensationMs + fineAdjustmentMs
    }
    if (fineAdjustmentMs == 0L && latencyCompensationMs == 0L) return null
    return wallNowMs + latencyCompensationMs + fineAdjustmentMs
}

internal fun learnedLatencyCompensationMs(samples: List<Long>): Long {
    val valid = samples
        .filter { it in MIN_LATENCY_SAMPLE_MS..MAX_LATENCY_SAMPLE_MS }
        .sorted()
    if (valid.isEmpty()) return 0L
    return valid[valid.size / 2].coerceAtMost(MAX_LATENCY_COMPENSATION_MS)
}

internal const val MAX_LATENCY_SAMPLE_COUNT = 7
internal const val MIN_LATENCY_SAMPLE_MS = 0L
internal const val MAX_LATENCY_SAMPLE_MS = 5_000L
/**
 * Cap on learned compensation. After the per-connection BLE cache is warm ("Mode 2"),
 * `setTime` returns in single-digit ms; only the BLE delivery jitter (typ. 10–40 ms) needs
 * compensating. Sub-300 ms samples represent Mode 2; we cap well below the Mode-1 floor to
 * avoid stale cold-start samples poisoning the learned value.
 */
internal const val MAX_LATENCY_COMPENSATION_MS = 200L
internal const val LATENCY_SAMPLE_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
internal const val DEFAULT_SEND_LATENCY_ESTIMATE_MS = 30L

internal fun shouldRecordLatencySample(
    skippedGenerationByAddress: MutableMap<String, Long>,
    address: String,
    generation: Long,
): Boolean {
    val shouldSkip = skippedGenerationByAddress[address] != generation
    if (shouldSkip) {
        skippedGenerationByAddress[address] = generation
        return false
    }
    return true
}

@Singleton
class WatchTimeProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sntpClient: SntpClient,
    private val watchSelector: CurrentWatchSelector,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun nowForWatch(): WatchTime {
        val fineAdjustment = LocalDataStorage.getFineTimeAdjustment(context).toLong()
        val latencyCompensation = watchSelector.currentAddress()
            ?.let { LocalDataStorage.getLearnedTimeWriteCompensation(context, it) }
            ?: 0L
        if (!LocalDataStorage.getNtpTimeEnabled(context)) {
            return WatchTime(
                ntpCache = null,
                fineAdjustmentMs = fineAdjustment,
                latencyCompensationMs = latencyCompensation,
                sourceLabel = "Phone time",
            )
        }

        val server = LocalDataStorage.getNtpTimeServer(context)
        val elapsedNow = SystemClock.elapsedRealtime()
        val cached = LocalDataStorage.getNtpOffsetCache(context)
        if (cached?.isFreshFor(server, elapsedNow) == true) {
            return WatchTime(
                ntpCache = cached,
                fineAdjustmentMs = fineAdjustment,
                latencyCompensationMs = latencyCompensation,
                sourceLabel = "NTP $server",
            )
        }

        return runCatching {
            refreshNtpOffset(server, FOREGROUND_NTP_TIMEOUT_MILLIS)
        }.map { cache ->
            WatchTime(
                ntpCache = cache,
                fineAdjustmentMs = fineAdjustment,
                latencyCompensationMs = latencyCompensation,
                sourceLabel = "NTP $server",
            )
        }.getOrElse { error ->
            refreshNtpInBackground()
            WatchTime(
                ntpCache = null,
                fineAdjustmentMs = fineAdjustment,
                latencyCompensationMs = latencyCompensation,
                sourceLabel = "Phone time",
                fallbackReason = "NTP failed: ${error.message ?: error::class.java.simpleName}; using phone time",
            )
        }
    }

    fun refreshNtpInBackground() {
        if (!LocalDataStorage.getNtpTimeEnabled(context)) return
        scope.launch {
            runCatching {
                refreshNtpOffset(LocalDataStorage.getNtpTimeServer(context), BACKGROUND_NTP_TIMEOUT_MILLIS)
            }
        }
    }

    private suspend fun refreshNtpOffset(server: String, timeoutMillis: Int): NtpOffsetCache {
        val result = sntpClient.sample(server, timeoutMillis)
        val cache = NtpOffsetCache(
            server = server,
            offsetMs = result.offsetMs,
            roundTripMs = result.roundTripMs,
            capturedElapsedRealtimeMs = result.elapsedRealtimeMs,
            ntpTimeAtCaptureMs = result.timeMs,
        )
        LocalDataStorage.setNtpOffsetCache(context, cache)
        return cache
    }

    companion object {
        private const val FOREGROUND_NTP_TIMEOUT_MILLIS = 1_000
        private const val BACKGROUND_NTP_TIMEOUT_MILLIS = 3_000
    }
}

@Singleton
class WatchTimeWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val watchSelector: CurrentWatchSelector,
    private val connectionTracker: com.beamburst.casswatch.data.local.WatchConnectionTracker,
) {
    private val skippedGenerationByAddress = mutableMapOf<String, Long>()

    internal fun shouldRecordLatencySample(address: String, generation: Long): Boolean {
        return shouldRecordLatencySample(skippedGenerationByAddress, address, generation)
    }

    private fun resolveNowMs(watchTime: WatchTime): Long {
        val raw = watchTime.resolveTimeMs(
            wallNowMs = System.currentTimeMillis(),
            elapsedNowMs = SystemClock.elapsedRealtime(),
        ) ?: System.currentTimeMillis().plus(watchTime.fineAdjustmentMs)
        return raw - gshockApiDstBugCompensationMs()
    }

    /**
     * GShockAPI 1.4.70's `TimeIO.sendToWatchSet` computes a DST adjustment as
     * `dstOffset_quarterHours * 60 * 15` and adds it to the epoch ms timestamp before
     * encoding for the watch. The constant `15` looks like a typo where `1000` (ms/sec)
     * was intended — net result is the watch is set forward by *seconds-treated-as-ms*
     * during DST (e.g. 3600 ms ≈ 3.6 s ahead for a 1-hour DST zone). We subtract the
     * same bogus offset here so the two cancel out and the watch shows real local time.
     * Reported upstream — remove once GShockAPI is patched.
     */
    private fun gshockApiDstBugCompensationMs(): Long {
        val zone = ZoneId.systemDefault()
        val dstSeconds = zone.rules.getDaylightSavings(Instant.now()).seconds
        return dstSeconds
    }

    /**
     * User-facing time write. Always uses the one-shot setTime path: in practice `api.setTime`'s
     * observed return duration reflects BLE-queue time rather than watch-side delivery, so the
     * aimed-at-second-boundary optimization can't be driven from learned latency. Until we have
     * a reliable end-to-end delivery measurement, manual sync mirrors the connect-time write,
     * matching its drift (~1 s) and "OK" confirmation behavior.
     */
    suspend fun write(api: IGShockAPI, watchTime: WatchTime): TimeWriteResult {
        val address = watchSelector.currentAddress()
        return writeColdPath(api, watchTime, address)
    }

    /**
     * Connect-time silent priming write. Always runs a single `setTime` to populate the
     * library's CachedIO entries and put the watch time close to correct while the user is
     * still watching the connect animation. Only marks the generation warmed if the call
     * actually came back under [WatchConnectionTracker.CACHE_WARM_THRESHOLD_MS] — that's
     * the only signal that a subsequent aimed-at-boundary write will land near the target.
     */
    suspend fun warmAndSet(api: IGShockAPI, watchTime: WatchTime): TimeWriteResult {
        val address = watchSelector.currentAddress()
        val timeMs = resolveNowMs(watchTime)
        val startMs = SystemClock.elapsedRealtime()
        runCatching { api.setTime(timeMs = timeMs) }
            .onFailure { Timber.w(it, "Cache-warm setTime failed") }
        val durationMs = SystemClock.elapsedRealtime() - startMs
        connectionTracker.markCacheWarmIfFast(address, durationMs)
        // Don't record latency samples from the cold warming write — they'd pollute the
        // Mode-2 distribution we use to estimate BLE send latency.
        if (address != null) {
            skippedGenerationByAddress[address] = connectionTracker.connectionGeneration(address)
        }
        return TimeWriteResult(
            timeMs = timeMs,
            durationMs = durationMs,
            compensationMs = watchTime.latencyCompensationMs,
        )
    }

    private suspend fun writeColdPath(
        api: IGShockAPI,
        watchTime: WatchTime,
        address: String?,
    ): TimeWriteResult {
        val timeMs = resolveNowMs(watchTime)
        val startMs = SystemClock.elapsedRealtime()
        api.setTime(timeMs = timeMs)
        val durationMs = SystemClock.elapsedRealtime() - startMs
        connectionTracker.markCacheWarmIfFast(address, durationMs)
        // Mode-1 samples mix cache-miss BLE reads into the latency; do not learn from them.
        if (address != null) {
            skippedGenerationByAddress[address] = connectionTracker.connectionGeneration(address)
        }
        return TimeWriteResult(
            timeMs = timeMs,
            durationMs = durationMs,
            compensationMs = watchTime.latencyCompensationMs,
        )
    }

    private suspend fun writeAimedAtSecondBoundary(
        api: IGShockAPI,
        watchTime: WatchTime,
        address: String?,
    ): TimeWriteResult {
        val estimateMs = address
            ?.let { LocalDataStorage.getLearnedTimeWriteCompensation(context, it) }
            ?.takeIf { it > 0L }
            ?: DEFAULT_SEND_LATENCY_ESTIMATE_MS

        val nowBefore = resolveNowMs(watchTime)
        val target = ((nowBefore / 1000L) + 1L) * 1000L
        val fireAt = target - estimateMs
        val waitMs = fireAt - nowBefore
        if (waitMs > 0L) delay(waitMs)

        val startMs = SystemClock.elapsedRealtime()
        var threw = false
        try {
            api.setTime(timeMs = target)
        } catch (t: Throwable) {
            threw = true
            throw t
        } finally {
            val durationMs = SystemClock.elapsedRealtime() - startMs
            // Successful Mode-2 samples are stable and small — record them for future
            // estimateMs improvement. Skip on throw to avoid biasing on partial failures.
            if (!threw && address != null) {
                val generation = connectionTracker.connectionGeneration(address)
                if (shouldRecordLatencySample(address, generation)) {
                    runCatching {
                        LocalDataStorage.addTimeWriteLatencySample(context, address, durationMs)
                    }
                }
            }
        }
        val durationMs = SystemClock.elapsedRealtime() - startMs
        return TimeWriteResult(
            timeMs = target,
            durationMs = durationMs,
            compensationMs = estimateMs,
        )
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

    suspend fun sample(
        server: String,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): SntpResult = withContext(Dispatchers.IO) {
        query(server, timeoutMillis)
    }

    private fun query(server: String, timeoutMillis: Int): SntpResult {
        val address = InetAddress.getByName(server)
        val buffer = ByteArray(NTP_PACKET_SIZE)
        buffer[0] = (NTP_MODE_CLIENT or NTP_VERSION).toByte()

        DatagramSocket().use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = timeoutMillis
            val requestTime = System.currentTimeMillis()
            val requestElapsed = SystemClock.elapsedRealtime()
            writeTimestamp(buffer, TRANSMIT_TIME_OFFSET, requestTime)
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))

            val response = DatagramPacket(ByteArray(NTP_PACKET_SIZE), NTP_PACKET_SIZE)
            socket.receive(response)
            val responseTime = System.currentTimeMillis()
            val responseElapsed = SystemClock.elapsedRealtime()
            val data = response.data
            val receiveTime = readTimestamp(data, RECEIVE_TIME_OFFSET)
            val transmitTime = readTimestamp(data, TRANSMIT_TIME_OFFSET)
            val offset = calculateOffset(
                requestTime = requestTime,
                receiveTime = receiveTime,
                transmitTime = transmitTime,
                responseTime = responseTime,
            )
            return SntpResult(
                timeMs = responseTime + offset,
                offsetMs = offset,
                roundTripMs = responseElapsed - requestElapsed,
                elapsedRealtimeMs = responseElapsed,
            )
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
    val roundTripMs: Long,
    val elapsedRealtimeMs: Long,
)

private fun formatSignedMs(value: Long): String =
    if (value >= 0L) "+${value}ms" else "${value}ms"

private fun formatAge(ageMs: Long): String {
    val safeAge = ageMs.coerceAtLeast(0L)
    return when {
        safeAge < 60_000L -> "${safeAge / 1_000L}s"
        safeAge < 60L * 60L * 1_000L -> "${safeAge / 60_000L}m"
        else -> "${safeAge / (60L * 60L * 1_000L)}h"
    }
}
