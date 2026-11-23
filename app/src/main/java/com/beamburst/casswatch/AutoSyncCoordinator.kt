package com.beamburst.casswatch

import com.beamburst.casswatch.pairing.confirmWatchWrite
import com.beamburst.casswatch.pairing.guardWrites
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.pairing.WatchConnectCoordinator
import com.beamburst.casswatch.R
import com.beamburst.casswatch.utils.WatchSyncActivity
import com.beamburst.casswatch.utils.AppScopes
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.beamburst.casswatch.pairing.WatchConnectionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchSettingsCache
import com.beamburst.casswatch.services.NotificationProvider
import com.beamburst.casswatch.steps.StepSyncer
import com.beamburst.casswatch.ui.alarms.HourlySignalEvaluator
import com.beamburst.casswatch.ui.alarms.WeeklyAlarmScheduler
import com.beamburst.casswatch.ui.alarms.applyReconciledHourlySignal
import com.beamburst.casswatch.ui.events.CalendarEvents
import com.beamburst.casswatch.ui.events.EventsModel
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.SyncHistoryLogger
import com.beamburst.casswatch.utils.WatchTimeProvider
import com.beamburst.casswatch.utils.WatchTimeWriter
import com.beamburst.casswatch.utils.syncHistoryActions
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import timber.log.Timber
import java.text.DateFormat
import java.time.Clock
import java.time.LocalDateTime
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: IGShockAPI,
    private val calendarEvents: CalendarEvents,
    private val notificationProvider: NotificationProvider,
    private val syncLogger: SyncHistoryLogger,
    private val session: WatchConnectionSession,
    private val coordinator: WatchConnectCoordinator,
    private val watchTimeProvider: WatchTimeProvider,
    private val watchTimeWriter: WatchTimeWriter,
    private val stepSyncer: StepSyncer,
    private val weeklyAlarmScheduler: WeeklyAlarmScheduler,
) {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "AutoSyncCoordinator"

        /** The watch may drop an auto-sync link within seconds; a slow NTP answer must not eat it. */
        const val AUTO_SYNC_NTP_WAIT_MS = 700L
    }

    private val scope = AppScopes.io

    init {
        ProgressEvents.runEventActions(EVENT_SUBSCRIBER_ID, arrayOf(
            EventAction("ButtonPressedInfoReceived") {
                if (api.isAutoTimeStarted()) scope.launch {
                    try {
                        runChain(
                            trigger = SyncTrigger.AUTO,
                            actions = listOf("Auto time adjustment requested by watch"),
                        )
                    } catch (_: WatchConnectionChangedException) {
                        // The watch ended the link; the chain has already recorded its outcome.
                    }
                }
            },
        ))
    }

    suspend fun runChain(
        trigger: SyncTrigger = SyncTrigger.AUTO,
        actions: List<String>? = null,
    ) {
        val ready = session.ready ?: return
        // Only a firmware auto-sync skipped the alarm schedule at initialization.
        val firmwareAutoSync = api.isAutoTimeStarted()
        val watchTime = watchTimeProvider.nowForWatch(ready.address, maxNtpWaitMs = AUTO_SYNC_NTP_WAIT_MS)
        val calendar = if (WatchInfo.hasReminders) runCatching { calendarEvents.getEventsFromCalendar() } else null
        coordinator.withSession(ready) { checkSession ->
            val api = this.api.guardWrites(checkSession)
            WatchSyncActivity.track {
                val kinds = mutableSetOf<SyncKind>()
                var success = true
                var error: String? = null
                var recordedTimeMs: Long? = null
                var recordedEventCount: Int? = null
                var recordedHourlyTarget: Boolean? = null
                val recordedActions = actions.orEmpty().toMutableList()
                // Which step was running, so a link the watch drops mid-chain is still diagnosable.
                var stage = "cache prime"

                runCatching {
                    val addr = ready.address
                    if (firmwareAutoSync) {
                        // The watch waits for the time (>= 15 s, tested 2026-11-22) but ends the
                        // link ~2 s after receiving it, without answering a step read started
                        // then (2026-11-23 01:55), so the steps and the alarm schedule go first.
                        // The step read closes the lifelog transaction: an open (peek) one keeps
                        // the watch waiting ~60 s after the time and it shows ERR (01:30). The
                        // closed records are gone from the watch; the phone keeps the history.
                        stage = "steps"
                        try {
                            checkSession()
                            stepSyncer.read(this@AutoSyncCoordinator.api, addr, trigger,
                                StepSyncer.AUTO_SYNC_BUDGET_MS, readGoal = false, closeTransaction = true)
                        } catch (error: Exception) {
                            rethrowIfLinkGone(error)
                            Timber.w(error, "Auto-sync step read failed")
                        }
                        stage = "alarms"
                        writeAlarmSchedule(ready, api, checkSession)
                        stage = "settings"
                        try {
                            // Only unsent user edits; a clean cache costs nothing.
                            WatchSettingsCache.reconcileOnConnect(context, addr, api, checkSession, refresh = false)
                        } catch (error: Exception) {
                            rethrowIfLinkGone(error)
                            Timber.w(error, "Auto-sync settings push failed; edits stay pending")
                        }
                        stage = "cache prime"
                    }
                    runCatching { watchTimeWriter.primeCache(api) }
                        .onFailure { Timber.w(it, "Auto-sync cache prime failed") }
                    stage = "time write"
                    val writeResult = watchTimeWriter.writeAfterPrime(api, watchTime, ready, checkSession)
                    kinds += SyncKind.TIME
                    recordedTimeMs = writeResult.timeMs
                    recordedActions += watchTime.syncHistoryActions("Automatic time write", writeResult)
                    if (!firmwareAutoSync) {
                        stage = "settings"
                        // Only pending user edits are pushed here; a clean cache costs no reads.
                        WatchSettingsCache.reconcileOnConnect(context, addr, api, checkSession, refresh = false)
                    }

                    if (calendar != null) {
                        stage = "events"
                        EventsModel.refresh(calendar.getOrThrow())
                        checkSession()
                        api.setEvents(EventsModel.events)
                        kinds += SyncKind.EVENTS
                        recordedEventCount = EventsModel.events.size
                    }

                    stage = "hourly signal"
                    val signalSettings = HourlySignalSettings.load(context, addr)
                    val now = LocalDateTime.now()
                    val target = HourlySignalEvaluator.shouldChimeAt(now, signalSettings)
                    checkSession()
                    applyReconciledHourlySignal(context, api.guardWrites(checkSession), ready,
                        signalSettings, now, trigger) { confirmWatchWrite(checkSession) }
                    kinds += SyncKind.HOURLY_SIGNAL
                    recordedHourlyTarget = target
                }.onFailure {
                    val linkGone = it is CancellationException || it is WatchConnectionChangedException
                    if (linkGone && recordedTimeMs != null) {
                        // The time is on the watch; the watch just ended the link early.
                        recordedActions += "Link ended during $stage"
                    } else {
                        success = false
                        error = "$stage: ${it.message}"
                    }
                }

                // Record the outcome even when the watch has already dropped the link: a lost
                // entry hides what happened.
                val linkEnded = runCatching { confirmWatchWrite(checkSession) }.isFailure
                if (linkEnded) recordedActions += "Link ended before the chain finished"
                if (kinds.isEmpty()) kinds += SyncKind.TIME
                // The session's work is cancelled when the link ends; the entry must still land.
                withContext(NonCancellable) {
                    runCatching { syncLogger.record(
                        address = ready.address,
                        trigger = trigger,
                        kinds = kinds,
                        success = success,
                        errorMessage = error,
                        details = SyncDetails(
                            alarmHashes = null,
                            eventCount = recordedEventCount,
                            hourlySignalTarget = recordedHourlyTarget,
                            timeMs = recordedTimeMs,
                            actions = recordedActions.ifEmpty { null },
                        ),
                    ) }.onFailure { Timber.w(it, "Auto-sync history entry not saved") }
                }

                // Recorded; nothing else may run once this work was cancelled.
                currentCoroutineContext().ensureActive()

                if (success && LocalDataStorage.getTimeAdjustmentNotification(context) && !WatchInfo.alwaysConnected) {
                    postSyncNotification()
                }
                // Prevent CDM from immediately reconnecting after the firmware-initiated sync disconnects,
                // which would cause a spurious cache-prime timeout in the sync log.
                checkSession()
                api.preventReconnection()

                // Other chains (always-connected button presses) read steps afterwards, queued
                // behind this work on the session's mutex.
                if (success && !firmwareAutoSync) {
                    stepSyncer.schedule(ready, trigger, StepSyncer.AUTO_SYNC_BUDGET_MS, readGoal = false)
                }
            }
        }
    }

    /** Alarm failures are recorded and do not stop the time write; a lost link does. */
    private suspend fun writeAlarmSchedule(
        ready: WatchConnectionSession.Ready,
        api: IGShockAPI,
        checkSession: () -> Unit,
    ) {
        try {
            weeklyAlarmScheduler.writeScheduleInSession(ready, api, checkSession)
        } catch (error: Exception) {
            rethrowIfLinkGone(error)
            Timber.w(error, "Auto-sync alarm schedule failed for %s", ready.address)
            withContext(NonCancellable) {
                runCatching {
                    syncLogger.record(
                        address = ready.address,
                        trigger = SyncTrigger.AUTO_ON_RECONNECT,
                        kinds = setOf(SyncKind.ALARMS),
                        success = false,
                        errorMessage = error.message,
                    )
                }
            }
        }
    }

    /**
     * Ended session work and a lost link stop the chain. A local operation timeout (also a
     * CancellationException) while this work is still active is an ordinary failure.
     */
    private suspend fun rethrowIfLinkGone(error: Exception) {
        if (error is WatchConnectionChangedException) throw error
        if (error is CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }

    private fun postSyncNotification() {
        val dateStr = DateFormat.getDateTimeInstance().format(Date(Clock.systemDefaultZone().millis()))
        val text = context.getString(R.string.time_set_notification, dateStr, WatchInfo.name)
        notificationProvider.createNotification(
            NotificationProvider.NotificationContent(title = "Cassiopeia Watch", text = text)
        )
    }
}
