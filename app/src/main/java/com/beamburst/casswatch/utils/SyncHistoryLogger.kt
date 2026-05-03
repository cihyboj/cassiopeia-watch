package com.beamburst.casswatch.utils

import com.beamburst.casswatch.data.local.CurrentWatchSelector
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
    private val watchSelector: CurrentWatchSelector,
) {
    suspend fun record(
        trigger: SyncTrigger,
        kinds: Set<SyncKind>,
        success: Boolean,
        errorMessage: String? = null,
        details: SyncDetails = SyncDetails.Empty,
    ) {
        val addr = watchSelector.currentAddress() ?: return
        store.append(
            SyncEntry(
                timestamp = System.currentTimeMillis(),
                deviceAddress = addr,
                trigger = trigger,
                kinds = kinds,
                success = success,
                errorMessage = errorMessage?.take(200),
                details = details,
            )
        )
    }
}
