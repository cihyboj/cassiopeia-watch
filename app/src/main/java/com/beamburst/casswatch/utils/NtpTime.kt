package com.beamburst.casswatch.utils

import com.beamburst.casswatch.pairing.WatchConnectionChangedException
import android.content.Context
import android.os.SystemClock
import com.beamburst.casswatch.data.local.WatchConnectionSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import org.avmedia.gshockapi.IGShockAPI
import timber.log.Timber

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
    val diagnostics: SyncDiagnostics? = null,
)

fun WatchTime.syncHistoryActions(
    primaryAction: String,
    writeResult: TimeWriteResult? = null,
): List<String> =
    buildList {
        add(primaryAction)
        add("Time source: $sourceLabel")
        fallbackReason?.let(::add)
        ntpDiagnostic(SystemClock.elapsedRealtime())?.let(::add)
        writeResult?.let {
            add("Time write duration: ${it.durationMs}ms; compensation: ${it.compensationMs}ms")
            it.diagnostics?.formatLines()?.forEach(::add)
        }
    }

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
    private val session: WatchConnectionSession,
) {
    private val scope = AppScopes.io

    suspend fun nowForWatch(address: String? = session.address): WatchTime {
        val fineAdjustment = LocalDataStorage.getFineTimeAdjustment(context).toLong()
        val latencyCompensation = address
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
        // soTimeout only bounds the socket read; DNS resolution inside sample() is
        // otherwise unbounded and could hang "Send time to watch" far past its budget.
        val result = withTimeout(timeoutMillis * 2L + 1_000L) {
            sntpClient.sample(server, timeoutMillis)
        }
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
    private val session: WatchConnectionSession,
    private val connectionTracker: com.beamburst.casswatch.data.local.WatchConnectionTracker,
    private val syncHistoryLogger: SyncHistoryLogger,
) {
    companion object {
        /**
         * Upper bound on `primeTimeCache`. Generous enough for the slowest legitimate
         * Mode-1 (DST + up to 6 World Cities BLE reads on a slow link), short enough to
         * detect a hung BLE stack and recover before the watch firmware gives up.
         */
        const val PRIME_TIMEOUT_MS = 8_000L

        /**
         * How long after the watch receives a setTime BLE packet before the encoded value
         * appears on the display. Empirically the Casio firmware schedules the display
         * change at the next internal 1 Hz tick after receipt — ~1000 ms when the BLE link
         * has been idle for several seconds (manual sync), ~800 ms when the link was busy
         * within the last second (auto sync, immediately after primeCache).
         */
        const val WATCH_ADOPTION_DELAY_MS = 1_000L
        const val WATCH_ADOPTION_DELAY_POST_PRIME_MS = 800L
    }

    enum class PrimeOutcome { OK, TIMEOUT, FAILURE }
    data class PrimeResult(val outcome: PrimeOutcome, val durationMs: Long, val error: Throwable? = null)

    private val skippedGenerationByAddress = mutableMapOf<String, Long>()

    internal fun shouldRecordLatencySample(address: String, generation: Long): Boolean {
        return shouldRecordLatencySample(skippedGenerationByAddress, address, generation)
    }

    private fun resolveNowMs(watchTime: WatchTime): Long {
        return watchTime.resolveTimeMs(
            wallNowMs = System.currentTimeMillis(),
            elapsedNowMs = SystemClock.elapsedRealtime(),
        ) ?: System.currentTimeMillis().plus(watchTime.fineAdjustmentMs)
    }

    /**
     * User-facing time write. When the BLE cache is warm (post-primeTimeCache), aims at the next
     * integer-second boundary: sleeps until `target − learned_latency`, then sends
     * `setTime(target)` so the watch adopts an exact integer second at approximately NTP-correct
     * moment. Sub-second alignment matters because empirically the watch firmware truncates
     * the hundredths byte in CURRENT_TIME — encoding "now mid-second" makes it adopt "now floored
     * to second" and the watch ends up ~500 ms behind on average (~1 s worst-case).
     *
     * Falls back to the cold-path (one-shot `setTime` with no boundary alignment) when the cache
     * is not yet warm — the 2–4 s Mode-1 round-trip makes pre-aiming useless and would push the
     * arriving packet far past the targeted second anyway.
     */
    suspend fun write(
        api: IGShockAPI,
        watchTime: WatchTime,
        ready: WatchConnectionSession.Ready = requireNotNull(session.ready),
        checkSelection: () -> Unit = {},
    ): TimeWriteResult {
        val address = ready.address
        val checkSession = {
            checkSelection()
            if (!session.isCurrent(ready) || !api.isConnected()) {
                throw WatchConnectionChangedException()
            }
        }
        checkSession()
        return if (connectionTracker.isCacheWarm(address)) {
            writeAimedAtSecondBoundary(api, watchTime, address, checkSession, WATCH_ADOPTION_DELAY_MS)
        } else {
            writeColdPath(api, watchTime, address, checkSession)
        }
    }

    /**
     * Auto-on-reconnect time write. Identical to [write] except the watch's observed adoption
     * delay is ~200 ms shorter when the setTime command is issued immediately after primeCache
     * finishes (the BLE link is hot, the watch processes the next packet faster). Pass the
     * shorter delay so the +2-boundary-aim's "fire one adoption-delay before target" lands the
     * watch's display update on the NTP boundary instead of ~200 ms early.
     */
    suspend fun writeAfterPrime(
        api: IGShockAPI,
        watchTime: WatchTime,
        ready: WatchConnectionSession.Ready = requireNotNull(session.ready),
        checkSelection: () -> Unit = {},
    ): TimeWriteResult {
        val address = ready.address
        val checkSession = {
            checkSelection()
            if (!session.isCurrent(ready) || !api.isConnected()) {
                throw WatchConnectionChangedException()
            }
        }
        checkSession()
        return if (connectionTracker.isCacheWarm(address)) {
            writeAimedAtSecondBoundary(api, watchTime, address, checkSession, WATCH_ADOPTION_DELAY_POST_PRIME_MS)
        } else {
            writeColdPath(api, watchTime, address, checkSession)
        }
    }

    /**
     * Connect-time silent cache priming. Calls the library's `primeTimeCache` which
     * performs the BLE round-trips (DST + WorldCities) without writing a timestamp,
     * so the watch never flashes a wrong time during the connect animation. A
     * subsequent user-facing `setTime` then hits the warm cache and lands within
     * ~50 ms of real time. Marks the generation warmed only if the prime returned
     * under [WatchConnectionTracker.CACHE_WARM_THRESHOLD_MS] — i.e. the cache was
     * already warm or the round-trips finished promptly.
     */
    suspend fun primeCache(api: IGShockAPI): PrimeResult {
        val ready = requireNotNull(session.ready)
        val address = ready.address
        val startMs = SystemClock.elapsedRealtime()
        val res = withTimeoutOrNull(PRIME_TIMEOUT_MS) {
            runCatching { api.primeTimeCache() }
        }
        val durationMs = SystemClock.elapsedRealtime() - startMs
        val result = when {
            res == null -> {
                Timber.w("primeTimeCache timed out after ${durationMs}ms")
                PrimeResult(PrimeOutcome.TIMEOUT, durationMs)
            }
            res.isFailure -> {
                val err = res.exceptionOrNull()
                Timber.w(err, "primeTimeCache failed")
                PrimeResult(PrimeOutcome.FAILURE, durationMs, err)
            }
            else -> {
                // A successful prime IS what populates the library's CachedIO, so the next
                // write is guaranteed Mode-2 regardless of how long the prime itself took.
                // markCacheWarmIfFast's 300ms threshold is wrong for primeTimeCache (which
                // legitimately takes 1.4–2.4s); unconditionally mark the generation warm.
                check(session.isCurrent(ready) && api.isConnected())
                connectionTracker.markCacheWarmed(address)
                PrimeResult(PrimeOutcome.OK, durationMs)
            }
        }
        runCatching {
            syncHistoryLogger.record(
                address = address,
                trigger = SyncTrigger.CONNECTION_CONNECTED,
                kinds = setOf(SyncKind.CONNECTION),
                success = result.outcome == PrimeOutcome.OK,
                errorMessage = when (result.outcome) {
                    PrimeOutcome.TIMEOUT -> "Cache prime timed out after ${PRIME_TIMEOUT_MS}ms"
                    PrimeOutcome.FAILURE -> result.error?.message ?: result.error?.javaClass?.simpleName
                    PrimeOutcome.OK -> null
                },
                details = SyncDetails(
                    alarmHashes = null,
                    eventCount = null,
                    hourlySignalTarget = null,
                    timeMs = null,
                    connection = "Cache prime",
                    actions = listOf(
                        "primeTimeCache: ${result.outcome.name.lowercase()} in ${durationMs}ms",
                    ),
                ),
            )
        }
        return result
    }

    private suspend fun writeColdPath(
        api: IGShockAPI,
        watchTime: WatchTime,
        address: String?,
        checkSession: () -> Unit,
    ): TimeWriteResult {
        val timeMs = resolveNowMs(watchTime)
        val cacheWarmAtStart = connectionTracker.isCacheWarm(address)
        val preSendWallMs = System.currentTimeMillis()
        val preSendElapsedMs = SystemClock.elapsedRealtime()
        checkSession()
        api.setTime(timeMs = timeMs)
        checkSession()
        val durationMs = SystemClock.elapsedRealtime() - preSendElapsedMs
        connectionTracker.markCacheWarmIfFast(address, durationMs)
        // Mode-1 samples mix cache-miss BLE reads into the latency; do not learn from them.
        if (address != null) {
            skippedGenerationByAddress[address] = connectionTracker.connectionGeneration(address)
        }
        val ntp = watchTime.ntpCache
        val diagnostics = SyncDiagnostics(
            encodedTimeMs = timeMs,
            preSendWallMs = preSendWallMs,
            ntpOffsetMs = ntp?.offsetMs,
            ntpRttMs = ntp?.roundTripMs,
            ntpAgeMs = ntp?.let { preSendElapsedMs - it.capturedElapsedRealtimeMs },
            durationMs = durationMs,
            cacheWarmAtWriteStart = cacheWarmAtStart,
        )
        return TimeWriteResult(
            timeMs = timeMs,
            durationMs = durationMs,
            compensationMs = watchTime.latencyCompensationMs,
            diagnostics = diagnostics,
        )
    }

    private suspend fun writeAimedAtSecondBoundary(
        api: IGShockAPI,
        watchTime: WatchTime,
        address: String?,
        checkSession: () -> Unit,
        adoptionDelayMs: Long,
    ): TimeWriteResult {
        val cacheWarmAtStart = connectionTracker.isCacheWarm(address)
        val estimateMs = address
            ?.let { LocalDataStorage.getLearnedTimeWriteCompensation(context, it) }
            ?.takeIf { it > 0L }
            ?: DEFAULT_SEND_LATENCY_ESTIMATE_MS

        // Casio watches apply a CURRENT_TIME write at the watch's next internal 1Hz tick
        // *after* receipt. Aim two seconds ahead and fire the packet (adoptionDelay + RTT)
        // before the target, so the watch's display change lands on the NTP boundary.
        val nowBefore = resolveNowMs(watchTime)
        val target = ((nowBefore / 1000L) + 2L) * 1000L
        val fireAt = target - adoptionDelayMs - estimateMs
        val waitMs = fireAt - nowBefore
        if (waitMs > 0L) delay(waitMs)

        val preSendWallMs = System.currentTimeMillis()
        val preSendElapsedMs = SystemClock.elapsedRealtime()
        try {
            checkSession()
            api.setTime(timeMs = target)
            checkSession()
        } catch (t: Throwable) {
            // Skip sample recording on throw to avoid biasing learned latency.
            throw t
        }
        val durationMs = SystemClock.elapsedRealtime() - preSendElapsedMs
        connectionTracker.markCacheWarmIfFast(address, durationMs)
        if (address != null) {
            val generation = connectionTracker.connectionGeneration(address)
            if (shouldRecordLatencySample(address, generation)) {
                runCatching {
                    LocalDataStorage.addTimeWriteLatencySample(context, address, durationMs)
                }
            }
        }
        val ntp = watchTime.ntpCache
        val diagnostics = SyncDiagnostics(
            encodedTimeMs = target,
            preSendWallMs = preSendWallMs,
            ntpOffsetMs = ntp?.offsetMs,
            ntpRttMs = ntp?.roundTripMs,
            ntpAgeMs = ntp?.let { preSendElapsedMs - it.capturedElapsedRealtimeMs },
            durationMs = durationMs,
            cacheWarmAtWriteStart = cacheWarmAtStart,
        )
        return TimeWriteResult(
            timeMs = target,
            durationMs = durationMs,
            compensationMs = estimateMs,
            diagnostics = diagnostics,
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

            // Validate the response before trusting it (mirrors AOSP SntpClient): a
            // Kiss-o'-Death packet, wrong mode, or zeroed transmit timestamp would
            // otherwise produce a wildly wrong offset that gets written to the watch
            // and cached for 30 minutes.
            val leap = (data[0].toInt() shr 6) and 0x3
            val mode = data[0].toInt() and 0x7
            val stratum = data[1].toInt() and 0xff
            if (leap == NTP_LEAP_NOSYNC) throw IOException("NTP server not synchronized (leap=3)")
            if (mode != NTP_MODE_SERVER && mode != NTP_MODE_BROADCAST) {
                throw IOException("Unexpected NTP mode: $mode")
            }
            if (stratum == NTP_STRATUM_DEATH || stratum > NTP_STRATUM_MAX) {
                throw IOException("Unacceptable NTP stratum: $stratum")
            }

            val receiveTime = readTimestamp(data, RECEIVE_TIME_OFFSET)
            val transmitTime = readTimestamp(data, TRANSMIT_TIME_OFFSET)
            if (transmitTime == -NTP_TO_UNIX_EPOCH_SECONDS * 1000L || transmitTime == 0L) {
                throw IOException("Zero NTP transmit timestamp")
            }
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
        private const val NTP_MODE_SERVER = 4
        private const val NTP_MODE_BROADCAST = 5
        private const val NTP_LEAP_NOSYNC = 3
        private const val NTP_STRATUM_DEATH = 0
        private const val NTP_STRATUM_MAX = 15
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

internal fun formatSignedMs(value: Long): String =
    if (value >= 0L) "+${value}ms" else "${value}ms"

private fun formatAge(ageMs: Long): String {
    val safeAge = ageMs.coerceAtLeast(0L)
    return when {
        safeAge < 60_000L -> "${safeAge / 1_000L}s"
        safeAge < 60L * 60L * 1_000L -> "${safeAge / 60_000L}m"
        else -> "${safeAge / (60L * 60L * 1_000L)}h"
    }
}
