package com.beamburst.casswatch.utils

import com.beamburst.casswatch.ui.alarms.AlarmSchedulingCoordinator
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncHistoryStore
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncHistoryLogger @Inject constructor(
    private val store: SyncHistoryStore,
    private val session: WatchConnectionSession,
    private val alarmCoordinator: AlarmSchedulingCoordinator,
) {
    suspend fun record(
        trigger: SyncTrigger,
        kinds: Set<SyncKind>,
        success: Boolean,
        errorMessage: String? = null,
        details: SyncDetails = SyncDetails.Empty,
        address: String? = session.address,
    ) {
        val addr = address ?: return
        // WatchTimeWriter's cache-prime diagnostics use the legacy connected trigger.
        // Keep those results in history without presenting another lifecycle event.
        val historyTrigger = if (trigger == SyncTrigger.CONNECTION_CONNECTED && details.actions != null) {
            SyncTrigger.AUTO_ON_RECONNECT
        } else trigger
        // Cache primes have no timeMs and cannot repair a disconnected watch's clock.
        // Existing time writers confirm their captured session before logging an actual write.
        val ready = session.matching(addr)
        if (success && SyncKind.TIME in kinds && details.timeMs != null && ready != null) {
            alarmCoordinator.update(ready.address) { state ->
                if (session.isCurrent(ready)) state.copy(clockNeedsSync = false) else state
            }
        }
        store.append(
            SyncEntry(
                timestamp = System.currentTimeMillis(),
                deviceAddress = addr,
                trigger = historyTrigger,
                kinds = kinds,
                success = success,
                errorMessage = errorMessage?.take(200),
                details = details,
            )
        )
    }
}
