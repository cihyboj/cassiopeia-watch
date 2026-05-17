package com.beamburst.casswatch.utils

object AppUsageState {
    @Volatile
    private var isForeground: Boolean = false

    @Volatile
    private var lastInteractionAtMs: Long = System.currentTimeMillis()

    fun markForeground() {
        isForeground = true
    }

    fun markBackground() {
        isForeground = false
    }

    fun registerInteraction(nowMs: Long = System.currentTimeMillis()) {
        lastInteractionAtMs = nowMs
    }

    fun shouldShowConnectionSnackbar(nowMs: Long = System.currentTimeMillis()): Boolean =
        ConnectionSnackbarGate.shouldShow(
            isForeground = isForeground,
            lastInteractionAtMs = lastInteractionAtMs,
            nowMs = nowMs,
        )
}
