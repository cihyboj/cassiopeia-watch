package com.beamburst.casswatch.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Per-write measurements attached to a successful `setTime` so we can reconstruct
 * after the fact where any observed watch-vs-real drift sits: in our timestamp
 * resolution, in NTP staleness, or in BLE delivery/watch-side rounding.
 *
 * All time fields are captured around the single `api.setTime(...)` call so the
 * deltas are interpretable without knowing thread scheduling.
 */
data class SyncDiagnostics(
    /** Epoch ms we encoded into the BLE packet (what we asked the watch to adopt). */
    val encodedTimeMs: Long,
    /** `System.currentTimeMillis()` captured immediately before `api.setTime`. */
    val preSendWallMs: Long,
    /** Cached NTP offset in ms at write time (null if NTP was off or stale). */
    val ntpOffsetMs: Long?,
    /** Round-trip ms of the NTP sample that produced [ntpOffsetMs]. */
    val ntpRttMs: Long?,
    /** Age of the NTP sample in ms at write time. */
    val ntpAgeMs: Long?,
    /** `api.setTime` round-trip in ms (post-send − pre-send, monotonic). */
    val durationMs: Long,
    /** Whether the connection tracker marked the cache warm *before* this write. */
    val cacheWarmAtWriteStart: Boolean,
) {
    fun formatLines(): List<String> {
        val deltaWall = encodedTimeMs - preSendWallMs
        val deltaNtp = ntpOffsetMs?.let { encodedTimeMs - (preSendWallMs + it) }
        val deltaLine = buildString {
            append("Δ vs phone wall = ${formatSignedMs(deltaWall)}")
            if (deltaNtp != null) append("  Δ vs NTP = ${formatSignedMs(deltaNtp)}")
        }
        val secondaryLine = buildString {
            append("BLE round-trip = ${durationMs} ms")
            if (ntpAgeMs != null) {
                append("  NTP age=${formatAgeSeconds(ntpAgeMs)}")
                ntpRttMs?.let { append(" rtt=${it} ms") }
                ntpOffsetMs?.let { append(" offset=${formatSignedMs(it)}") }
            }
            append("  cache warm at start=$cacheWarmAtWriteStart")
        }
        return listOf(
            "encoded → set: ${ISO_FORMATTER.format(Instant.ofEpochMilli(encodedTimeMs))}",
            deltaLine,
            secondaryLine,
        )
    }

    companion object {
        private val ISO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
    }
}

private fun formatAgeSeconds(ageMs: Long): String {
    val safe = ageMs.coerceAtLeast(0L)
    return when {
        safe < 60_000L -> "${safe / 1_000L}s"
        safe < 60L * 60L * 1_000L -> "${safe / 60_000L}m"
        else -> "${safe / (60L * 60L * 1_000L)}h"
    }
}
