package com.beamburst.casswatch.ui.devices

import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import com.beamburst.casswatch.data.local.WatchSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchRowSubtitleFormatterTest {
    @Test
    fun `disconnected with prior history and no cached metrics shows disconnected`() {
        assertEquals(
            "Disconnected",
            subtitle(
                state = ConnectionState.DISCONNECTED,
                snapshot = null,
                hasSeenWatch = true,
            )
        )
    }

    @Test
    fun `disconnected with cached battery and temperature includes cached metrics`() {
        assertEquals(
            "Disconnected • cached: 87% • 21°C",
            subtitle(
                state = ConnectionState.DISCONNECTED,
                snapshot = WatchSnapshot(
                    battery = 87,
                    temperature = 21,
                    homeTime = null,
                    cachedAt = 1000L,
                ),
                hasSeenWatch = true,
            )
        )
    }

    @Test
    fun `disconnected with no history asks user to connect`() {
        assertEquals(
            "Connect to read watch",
            subtitle(
                state = ConnectionState.DISCONNECTED,
                snapshot = null,
                hasSeenWatch = false,
            )
        )
    }

    @Test
    fun `connected with snapshot keeps metric subtitle`() {
        assertEquals(
            "87% • 21°C • Home time 12:34",
            subtitle(
                state = ConnectionState.CONNECTED,
                snapshot = WatchSnapshot(
                    battery = 87,
                    temperature = 21,
                    homeTime = "12:34",
                    cachedAt = 1000L,
                ),
                hasSeenWatch = true,
            )
        )
    }

    private fun subtitle(
        state: ConnectionState,
        snapshot: WatchSnapshot?,
        hasSeenWatch: Boolean,
    ): String =
        formatWatchRowSubtitle(
            state = state,
            snapshot = snapshot,
            hasSeenWatch = hasSeenWatch,
            homeTimeLabel = "Home time",
            connectedLabel = "Connected",
            connectingLabel = "Connecting...",
            connectionErrorLabel = "Connection error",
            disconnectedLabel = "Disconnected",
            connectToReadWatchLabel = "Connect to read watch",
            cachedPrefix = { "cached: $it" },
        )
}
