package com.beamburst.casswatch.steps

import android.content.Context
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.utils.SyncHistoryLogger
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.pairing.WatchConnectCoordinator
import com.beamburst.casswatch.pairing.WatchConnectionChangedException
import com.beamburst.casswatch.pairing.guardWrites
import com.beamburst.casswatch.utils.AppScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.WatchInfo
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the step counter at the end of a connection's work and merges it into [StepHistoryStore].
 *
 * Only ever a peek: closing the lifelog transaction deletes the watch's history. Runs after the
 * time/alarm results are recorded, so a slow or failed read never affects them.
 */
@Singleton
class StepSyncer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: StepHistoryStore,
    private val syncLogger: SyncHistoryLogger,
    private val api: IGShockAPI,
    private val coordinator: WatchConnectCoordinator,
) {
    /**
     * Queues a read as separate session work. The session's work mutex is FIFO, so the read
     * runs after the time, alarm and settings work already queued for this connection, and
     * never holds the session while that work waits. Dropped if the connection ends first.
     */
    fun schedule(ready: WatchConnectionSession.Ready, trigger: SyncTrigger, budgetMs: Long, readGoal: Boolean) {
        if (!WatchInfo.hasStepCounter || !StepHistoryStore.isReadingEnabled(context)) return
        AppScopes.io.launch {
            try {
                coordinator.withSession(ready) { check ->
                    read(api.guardWrites(check), ready.address, trigger, budgetMs, readGoal)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: WatchConnectionChangedException) {
                // The link ended before the read could start; nothing to record.
            } catch (e: Exception) {
                Timber.w(e, "Step read could not run")
            }
        }
    }

    /**
     * @param api the session-guarded API of the current connection.
     * @param budgetMs how long the read may take; firmware auto-syncs drop the link ~8-10 s in.
     * @param readGoal also read the step goal (at most once a day).
     */
    suspend fun read(
        api: IGShockAPI,
        address: String,
        trigger: SyncTrigger,
        budgetMs: Long,
        readGoal: Boolean,
        clock: () -> Long = System::currentTimeMillis,
    ) {
        if (!WatchInfo.hasStepCounter || !StepHistoryStore.isReadingEnabled(context)) return
        val actions = mutableListOf<String>()
        var success = true
        var error: String? = null
        try {
            val data = withTimeoutOrNull(budgetMs) { api.getStepCount(peek = true) }
            when {
                data == null -> {
                    success = false
                    error = "Step read timed out after $budgetMs ms"
                }
                !StepHistoryMerge.isUsable(data) -> {
                    success = false
                    error = "Step record unusable: ${data.warnings.joinToString("; ").ifEmpty { "no data" }}"
                }
                else -> {
                    store.update(address) { StepHistoryMerge.merge(it, data, clock()) }
                    actions += "Steps today: ${data.currentDaySteps}"
                }
            }
            if (readGoal && success) {
                val today = LocalDate.now()
                if (store.load(address).goalReadOn != today) {
                    withTimeoutOrNull(budgetMs) { api.getExerciseProfile() }?.let { profile ->
                        store.update(address) { StepHistoryMerge.withGoal(it, profile.stepGoal, today) }
                        actions += "Step goal: ${profile.stepGoal}"
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (gone: WatchConnectionChangedException) {
            // The watch ended the link (firmware auto-sync lasts ~8-10 s): not a failure.
            Timber.i("Step read skipped: connection ended")
            return
        } catch (e: Exception) {
            Timber.w(e, "Step read failed")
            success = false
            error = e.message
        }
        syncLogger.record(
            address = address,
            trigger = trigger,
            kinds = setOf(SyncKind.STEPS),
            success = success,
            errorMessage = error,
            details = SyncDetails(
                alarmHashes = null,
                eventCount = null,
                hourlySignalTarget = null,
                timeMs = null,
                actions = actions.ifEmpty { null },
            ),
        )
    }

    companion object {
        /** Firmware auto-sync: whatever is left of the ~8-10 s link after the time write. */
        const val AUTO_SYNC_BUDGET_MS = 3_000L
        const val NORMAL_CONNECTION_BUDGET_MS = 8_000L
    }
}
