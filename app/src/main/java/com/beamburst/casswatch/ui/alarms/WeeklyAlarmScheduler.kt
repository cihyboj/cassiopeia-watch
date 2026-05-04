package com.beamburst.casswatch.ui.alarms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.utils.SyncHistoryLogger
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeeklyAlarmScheduler @Inject constructor(
    private val api: GShockRepository,
    private val syncLogger: SyncHistoryLogger,
    private val watchSelector: CurrentWatchSelector,
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "WeeklyAlarmScheduler"
    }

    init {
        ProgressEvents.runEventActions(
            EVENT_SUBSCRIBER_ID,
            arrayOf(
                EventAction("WatchInitializationCompleted") {
                    CoroutineScope(Dispatchers.IO).launch { applyTodaySchedule() }
                }
            )
        )
    }

    fun ensureInitialized() = Unit

    suspend fun applyTodaySchedule() {
        val addr = watchSelector.currentAddress() ?: return

        val viewMode = AlarmSyncStorage.loadViewMode(context, addr)
        val alarmDays = AlarmSyncStorage.loadDaySelections(context, addr)
        val storedAlarms = AlarmSyncStorage.loadAlarms(context, addr)
        val firedAts = AlarmSyncStorage.loadFiredAts(context, addr)
        val shouldWrite = AlarmSyncStorage.isDirty(context, addr) || viewMode == AlarmViewMode.WEEKLY

        if (!shouldWrite) return

        val currentAlarms = runCatching { api.getAlarms() }.getOrElse {
            Timber.e(it, "Failed to get alarms before applying local schedule")
            return
        }

        val desiredAlarms = storedAlarms?.takeIf { it.isNotEmpty() } ?: currentAlarms
        val now = LocalDateTime.now()
        val signalSettings = HourlySignalSettings.load(context, addr)
        val hourlySignalTarget = HourlySignalEvaluator.shouldChimeAt(now, signalSettings)
        val scheduledAlarms = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = desiredAlarms,
            alarmDays = alarmDays,
            now = now,
            viewMode = viewMode,
            firedAts = firedAts
        )
        val alarmsToSend = if (WatchInfo.chimeInSettings) {
            scheduledAlarms
        } else {
            AlarmSchedulePlanner.mirrorChime(scheduledAlarms, hourlySignalTarget)
        }

        if (alarmsToSend != currentAlarms) {
            val setResult = runCatching {
                api.setAlarms(ArrayList(alarmsToSend))
                if (WatchInfo.chimeInSettings) {
                    applyHourlySignalToWatch(api, signalSettings, now)
                }
            }
            syncLogger.record(
                trigger = SyncTrigger.AUTO_ON_RECONNECT,
                kinds = alarmSyncHistoryKinds,
                success = setResult.isSuccess,
                errorMessage = setResult.exceptionOrNull()?.message,
                details = alarmSyncDetailsForWatchPayload(
                    alarms = alarmsToSend,
                    hourlySignalTarget = hourlySignalTarget,
                    actions = listOf("Weekly alarm schedule applied on reconnect"),
                ),
            )

            setResult.onFailure {
                Timber.e(it, "Failed to apply per-alarm day schedule")
            }.onSuccess {
                AlarmSyncStorage.saveAlarms(context, addr, desiredAlarms, dirty = false, firedAts = firedAts)
            }
        } else if (AlarmSyncStorage.isDirty(context, addr)) {
            AlarmSyncStorage.saveAlarms(context, addr, desiredAlarms, dirty = false, firedAts = firedAts)
        }
    }
}
