package com.beamburst.casswatch.utils

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks in-flight watch operations (time writes, alarm/event/settings syncs) so the
 * inactivity timer never tears down the BLE link in the middle of one.
 */
object WatchSyncActivity {
    private val inFlight = AtomicInteger(0)

    @Volatile
    private var lastActivityMs = 0L

    fun isBusy(): Boolean = inFlight.get() > 0

    /** True while an operation runs or within [graceMs] after the last one finished. */
    fun busyOrRecentlyActive(graceMs: Long): Boolean =
        isBusy() || System.currentTimeMillis() - lastActivityMs < graceMs

    fun begin() {
        inFlight.incrementAndGet()
        lastActivityMs = System.currentTimeMillis()
    }

    fun end() {
        inFlight.decrementAndGet()
        lastActivityMs = System.currentTimeMillis()
    }

    /** Inline so suspending calls and non-local returns inside [block] keep working. */
    inline fun <T> track(block: () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }
}
