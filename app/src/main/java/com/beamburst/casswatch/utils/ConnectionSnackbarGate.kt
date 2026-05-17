package com.beamburst.casswatch.utils

object ConnectionSnackbarGate {
    const val RECENT_INTERACTION_WINDOW_MS: Long = 3 * 60 * 1000L

    fun shouldShow(
        isForeground: Boolean,
        lastInteractionAtMs: Long,
        nowMs: Long,
        recentInteractionWindowMs: Long = RECENT_INTERACTION_WINDOW_MS,
    ): Boolean {
        if (!isForeground) return false
        if (lastInteractionAtMs > nowMs) return true
        return nowMs - lastInteractionAtMs < recentInteractionWindowMs
    }
}
