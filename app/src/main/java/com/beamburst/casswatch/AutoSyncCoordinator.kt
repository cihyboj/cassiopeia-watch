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
import kotlinx.coroutines.launch
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchSettingsCache
import com.beamburst.casswatch.services.NotificationProvider
import com.beamburst.casswatch.ui.alarms.HourlySignalEvaluator
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
) {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "AutoSyncCoordinator"
    }

    private val scope = AppScopes.io

    init {
        ProgressEvents.runEventActions(EVENT_SUBSCRIBER_ID, arrayOf(
            EventAction("ButtonPressedInfoReceived") {
                if (api.isAutoTimeStarted()) scope.launch {
                    runChain(
                        trigger = SyncTrigger.AUTO,
                        actions = listOf("Auto time adjustment requested by watch"),
                    )
                }
            },
        ))
    }

    suspend fun runChain(
        trigger: SyncTrigger = SyncTrigger.AUTO,
        actions: List<String>? = null,
    ) {
        val ready = session.ready ?: return
        val watchTime = watchTimeProvider.nowForWatch(ready.address)
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

                runCatching {
                    val addr = ready.address
                    WatchSettingsCache.reconcileOnConnect(context, addr, api, checkSession)
                    runCatching { watchTimeWriter.primeCache(api) }
                        .onFailure { Timber.w(it, "Auto-sync cache prime failed") }
                    val writeResult = watchTimeWriter.writeAfterPrime(api, watchTime, ready, checkSession)
                    kinds += SyncKind.TIME
                    recordedTimeMs = writeResult.timeMs
                    recordedActions += watchTime.syncHistoryActions("Automatic time write", writeResult)

                    if (calendar != null) {
                        EventsModel.refresh(calendar.getOrThrow())
                        checkSession()
                        api.setEvents(EventsModel.events)
                        kinds += SyncKind.EVENTS
                        recordedEventCount = EventsModel.events.size
                    }

                    val signalSettings = HourlySignalSettings.load(context, addr)
                    val now = LocalDateTime.now()
                    val target = HourlySignalEvaluator.shouldChimeAt(now, signalSettings)
                    checkSession()
                    applyReconciledHourlySignal(context, api.guardWrites(checkSession), ready,
                        signalSettings, now, trigger) { confirmWatchWrite(checkSession) }
                    kinds += SyncKind.HOURLY_SIGNAL
                    recordedHourlyTarget = target
                }.onFailure {
                    success = false
                    error = it.message
                }

                confirmWatchWrite(checkSession)
                syncLogger.record(
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
                )

                if (success && LocalDataStorage.getTimeAdjustmentNotification(context) && !WatchInfo.alwaysConnected) {
                    postSyncNotification()
                }
                // Prevent CDM from immediately reconnecting after the firmware-initiated sync disconnects,
                // which would cause a spurious cache-prime timeout in the sync log.
                checkSession()
                api.preventReconnection()
            }
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
