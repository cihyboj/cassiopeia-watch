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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.model.StepCounterData
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the step counter at the end of a connection's work and merges it into [StepHistoryStore].
 *
 * Normal connections peek: the transaction stays open and the watch keeps its records. Firmware
 * auto-syncs close it (`04 11`), because an open transaction keeps the watch waiting ~60 s and it
 * then reports the sync as failed (ERR, seen 2026-11-23). Closing consumes the hourly and daily
 * records on the watch, so the phone's history is the only copy from then on. Runs after the
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
     * @param closeTransaction end the watch's lifelog transaction after the read. The watch then
     *   drops the hourly and daily records it returned; the raw record is kept for diagnosis.
     *   Pass the unguarded API after checking the session: a guarded call re-checks the session
     *   after the read and would discard records the watch has already let go of.
     */
    suspend fun read(
        api: IGShockAPI,
        address: String,
        trigger: SyncTrigger,
        budgetMs: Long,
        readGoal: Boolean,
        closeTransaction: Boolean = false,
        clock: () -> Long = System::currentTimeMillis,
    ) {
        if (!WatchInfo.hasStepCounter || !StepHistoryStore.isReadingEnabled(context)) return
        if (closeTransaction) {
            readAndClose(api, address, trigger, budgetMs, clock)
            return
        }
        var data: StepCounterData? = null
        var readError: String? = null
        try {
            data = withTimeoutOrNull(budgetMs) { api.getStepCount(peek = true) }
            if (data == null) readError = "Step read timed out after $budgetMs ms"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (gone: WatchConnectionChangedException) {
            // The watch ended the link (firmware auto-sync lasts ~8-10 s): not a failure.
            Timber.i("Step read skipped: connection ended")
            return
        } catch (e: Exception) {
            Timber.w(e, "Step read failed")
            readError = e.message ?: e::class.java.simpleName
        }
        val actions = persist(address, trigger, data, readError, closed = false, clock = clock, record = !readGoal)
        if (!readGoal || actions == null) return
        var goalError: String? = null
        try {
            val today = LocalDate.now()
            if (store.load(address).goalReadOn != today) {
                withTimeoutOrNull(budgetMs) { api.getExerciseProfile() }?.let { profile ->
                    store.update(address) { StepHistoryMerge.withGoal(it, profile.stepGoal, today) }
                    actions += "Step goal read"
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (gone: WatchConnectionChangedException) {
            Timber.i("Step goal read skipped: connection ended")
        } catch (e: Exception) {
            Timber.w(e, "Step goal read failed")
            goalError = "Step goal: ${e.message ?: e::class.java.simpleName}"
        }
        recordResult(address, trigger, success = goalError == null, error = goalError, actions = actions)
    }

    /**
     * The library sends the close as soon as the whole record has arrived, so from then on the
     * phone holds the only copy. The read and the save therefore run outside the caller's
     * coroutine: cancelling the session (link drop) or the caller's [budgetMs] cannot drop a
     * record the watch has already let go of. The caller only waits for the read itself, at
     * most [budgetMs]: a slower read may finish after the caller's next writes (the time), but
     * holding the time back for it would risk the sync itself; the record is saved either way.
     */
    private suspend fun readAndClose(
        api: IGShockAPI,
        address: String,
        trigger: SyncTrigger,
        budgetMs: Long,
        clock: () -> Long,
    ) {
        val readDone = CompletableDeferred<Unit>()
        val generation = store.generation(address)
        AppScopes.io.launch(NonCancellable) {
            var data: StepCounterData? = null
            var readError: String? = null
            try {
                // The library ends a pending read when the link drops; this is only a backstop.
                data = withTimeoutOrNull(CLOSING_READ_LIMIT_MS) { api.getStepCount(peek = false) }
                if (data == null) readError = "Step read timed out after $CLOSING_READ_LIMIT_MS ms"
            } catch (gone: WatchConnectionChangedException) {
                Timber.i("Step read skipped: connection ended")
                readDone.complete(Unit)
                return@launch
            } catch (e: Exception) {
                Timber.w(e, "Step read failed")
                readError = e.message ?: e::class.java.simpleName
            }
            readDone.complete(Unit)
            persist(address, trigger, data, readError, closed = true, clock = clock, generation = generation)
        }
        withTimeoutOrNull(budgetMs) { readDone.await() }
    }

    /**
     * Merges a reading into the store. Returns the history actions when the reading was usable
     * and [record] is false (the caller records after adding to them), else records and
     * returns null for a failure.
     */
    private suspend fun persist(
        address: String,
        trigger: SyncTrigger,
        data: StepCounterData?,
        readError: String?,
        closed: Boolean,
        clock: () -> Long,
        record: Boolean = true,
        generation: Long? = null,
    ): MutableList<String>? {
        val actions = mutableListOf<String>()
        var error = readError
        try {
            if (closed && data?.raw != null) {
                // The library closes the transaction as soon as the whole record has arrived.
                actions += "Transaction closed"
                store.saveRaw(address, data.raw!!, clock(), generation)
            }
            when {
                data == null -> Unit
                !StepHistoryMerge.isUsable(data) -> {
                    // Warnings can quote step counts; sync history is backed up, so log them only.
                    Timber.w("Step record unusable: %s", data.warnings)
                    error = "Step record unusable"
                }
                else -> {
                    store.update(address, generation) { StepHistoryMerge.merge(it, data, clock()) }
                    // Sync history is in Android backup; step counts stay in the step store only.
                    actions += "Step history updated"
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.w(e, "Step history not saved")
            error = e.message ?: "Step history not saved"
        }
        if (error != null) {
            recordResult(address, trigger, success = false, error = error, actions = actions)
            return null
        }
        if (record) recordResult(address, trigger, success = true, error = null, actions = actions)
        return actions
    }

    private suspend fun recordResult(
        address: String,
        trigger: SyncTrigger,
        success: Boolean,
        error: String?,
        actions: List<String>,
    ) {
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
        /** Backstop for a closing read that outlives its caller's budget. */
        private const val CLOSING_READ_LIMIT_MS = 30_000L

        /** Firmware auto-sync: whatever is left of the ~8-10 s link after the time write. */
        const val AUTO_SYNC_BUDGET_MS = 3_000L
        const val NORMAL_CONNECTION_BUDGET_MS = 8_000L
    }
}
