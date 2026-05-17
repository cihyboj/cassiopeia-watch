package com.beamburst.casswatch.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSnackbarGateTest {
    private val windowMs = ConnectionSnackbarGate.RECENT_INTERACTION_WINDOW_MS

    @Test
    fun foregroundAndRecentInteractionShowsConnectionSnackbar() {
        val now = 10_000L

        assertTrue(
            ConnectionSnackbarGate.shouldShow(
                isForeground = true,
                lastInteractionAtMs = now - 1_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun backgroundSuppressesConnectionSnackbar() {
        val now = 10_000L

        assertFalse(
            ConnectionSnackbarGate.shouldShow(
                isForeground = false,
                lastInteractionAtMs = now - 1_000L,
                nowMs = now,
            )
        )
    }

    @Test
    fun staleForegroundInteractionSuppressesConnectionSnackbar() {
        val now = 10_000L

        assertFalse(
            ConnectionSnackbarGate.shouldShow(
                isForeground = true,
                lastInteractionAtMs = now - windowMs,
                nowMs = now,
            )
        )
    }

    @Test
    fun recentInteractionAfterReturningForegroundAllowsConnectionSnackbar() {
        val now = 10_000L

        assertFalse(
            ConnectionSnackbarGate.shouldShow(
                isForeground = true,
                lastInteractionAtMs = now - windowMs,
                nowMs = now,
            )
        )
        assertTrue(
            ConnectionSnackbarGate.shouldShow(
                isForeground = true,
                lastInteractionAtMs = now,
                nowMs = now,
            )
        )
    }
}
