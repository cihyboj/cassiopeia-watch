package com.beamburst.casswatch.ui.devices

import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import com.beamburst.casswatch.data.local.WatchSnapshot

internal fun formatWatchRowSubtitle(
    state: ConnectionState,
    snapshot: WatchSnapshot?,
    hasSeenWatch: Boolean,
    homeTimeLabel: String,
    connectedLabel: String,
    connectingLabel: String,
    connectionErrorLabel: String,
    disconnectedLabel: String,
    connectToReadWatchLabel: String,
    cachedPrefix: (String) -> String,
): String =
    when (state) {
        ConnectionState.CONNECTED -> listOfNotNull(
            snapshot?.battery?.let { "$it%" },
            snapshot?.temperature?.let { "$it°C" },
            snapshot?.homeTime?.let { "$homeTimeLabel $it" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" • ")
            ?: connectedLabel
        ConnectionState.CONNECTING -> connectingLabel
        ConnectionState.ERROR -> connectionErrorLabel
        ConnectionState.DISCONNECTED -> {
            val cached = listOfNotNull(
                snapshot?.battery?.let { "$it%" },
                snapshot?.temperature?.let { "$it°C" },
            )
            when {
                cached.isNotEmpty() ->
                    "$disconnectedLabel • ${cachedPrefix(cached.joinToString(" • "))}"
                hasSeenWatch -> disconnectedLabel
                else -> connectToReadWatchLabel
            }
        }
    }
