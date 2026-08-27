package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.utils.WatchSyncActivity
import com.beamburst.casswatch.utils.AppScopes
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchSettingsCache
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.SyncHistoryLogger
import com.beamburst.casswatch.utils.WatchTimeProvider
import com.beamburst.casswatch.utils.WatchTimeWriter
import com.beamburst.casswatch.utils.syncHistoryActions
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
    @param:ApplicationContext private val context: Context,
    private val watchTimeProvider: WatchTimeProvider,
    private val watchTimeWriter: WatchTimeWriter,
) {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "WeeklyAlarmScheduler"
    }

    init {
        ProgressEvents.runEventActions(
            EVENT_SUBSCRIBER_ID,
            arrayOf(
                EventAction("WatchInitializationCompleted") {
                    AppScopes.io.launch { applyTodaySchedule() }
                }
            )
        )
    }

    fun ensureInitialized() = Unit

    suspend fun applyTodaySchedule() = WatchSyncActivity.track {
        val addr = watchSelector.currentAddress() ?: return
        val autoTimeSyncEnabled = LocalDataStorage.getOrInitializeAutoSyncOnReconnect(
            context = context,
            address = addr,
            seed = WatchSettingsCache.load(context, addr)?.timeAdjustment,
        )
        if (autoTimeSyncEnabled) {
            runAutoTimeSyncOnReconnect()
        }

        val alarmDays = AlarmSyncStorage.loadDaySelections(context, addr)
        val storedAlarms = AlarmSyncStorage.loadAlarms(context, addr)
        val firedAts = AlarmSyncStorage.loadFiredAts(context, addr)
        val shouldWrite = AlarmSyncStorage.isDirty(context, addr) || !storedAlarms.isNullOrEmpty()

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
            viewMode = AlarmViewMode.WEEKLY,
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
                    actions = listOf("Alarm schedule applied on reconnect"),
                ),
            )

            setResult.onFailure {
                Timber.e(it, "Failed to apply per-alarm day schedule")
            }.onSuccess {
                AlarmSyncStorage.saveAlarms(context, addr, desiredAlarms, dirty = false, firedAts = firedAts)
            }
        } else {
            syncLogger.record(
                trigger = SyncTrigger.AUTO_ON_RECONNECT,
                kinds = alarmSyncHistoryKinds,
                success = true,
                details = alarmSyncDetailsForWatchPayload(
                    alarms = alarmsToSend,
                    hourlySignalTarget = hourlySignalTarget,
                    actions = listOf("Alarm schedule verified on reconnect"),
                ),
            )
            if (AlarmSyncStorage.isDirty(context, addr)) {
                AlarmSyncStorage.saveAlarms(context, addr, desiredAlarms, dirty = false, firedAts = firedAts)
            }
        }
    }

    private suspend fun runAutoTimeSyncOnReconnect() {
        // Firmware-initiated syncs (isAutoTimeStarted) are handled by AutoSyncCoordinator,
        // which primes the cache and calls writeAfterPrime itself. Skip here to avoid
        // a conflicting concurrent time write.
        if (api.isAutoTimeStarted()) return

        // GShockAPI's CachedIO is cold on a fresh connection — the first setTime takes
        // 2-3s (Mode 1). Subsequent setTimes return in ~5ms (Mode 2). Prime the cache
        // first (no timestamp written, no visible effect on the watch), then the
        // user-facing write below hits the warm cache and lands within <500ms.
        runCatching {
            watchTimeWriter.primeCache(api)
        }.onFailure { Timber.w(it, "Auto-reconnect cache prime failed") }

        val watchTime = watchTimeProvider.nowForWatch()
        var writeResult: com.beamburst.casswatch.utils.TimeWriteResult? = null
        val result = runCatching {
            writeResult = watchTimeWriter.writeAfterPrime(api, watchTime)
        }
        syncLogger.record(
            trigger = SyncTrigger.AUTO_ON_RECONNECT,
            kinds = setOf(SyncKind.TIME),
            success = result.isSuccess,
            errorMessage = result.exceptionOrNull()?.message,
            details = SyncDetails(
                alarmHashes = null,
                eventCount = null,
                hourlySignalTarget = null,
                timeMs = writeResult?.timeMs,
                actions = watchTime.syncHistoryActions(
                    primaryAction = "Automatic reconnect time write",
                    writeResult = writeResult,
                ),
            ),
        )
    }
}
