package com.beamburst.casswatch.ui.alarms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.utils.SyncHistoryLogger
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
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
        val newAlarms = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = desiredAlarms,
            alarmDays = alarmDays,
            now = LocalDateTime.now(),
            viewMode = viewMode,
            firedAts = firedAts
        )

        if (newAlarms != currentAlarms) {
            val hashes = desiredAlarms.mapIndexed { index, alarm ->
                val days = alarmDays[index] ?: emptySet()
                alarmHash(alarm.hour, alarm.minute, days, alarm.enabled)
            }

            val setResult = runCatching { api.setAlarms(ArrayList(newAlarms)) }
            syncLogger.record(
                trigger = SyncTrigger.AUTO_ON_RECONNECT,
                kinds = setOf(SyncKind.ALARMS),
                success = setResult.isSuccess,
                errorMessage = setResult.exceptionOrNull()?.message,
                details = SyncDetails(
                    alarmHashes = hashes,
                    eventCount = null,
                    hourlySignalTarget = null,
                    timeMs = null,
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
