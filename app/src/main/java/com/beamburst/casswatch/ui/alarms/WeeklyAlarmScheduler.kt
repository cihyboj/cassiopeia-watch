package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.pairing.confirmWatchWrite
import com.beamburst.casswatch.pairing.guardWrites
import com.beamburst.casswatch.pairing.WatchConnectCoordinator
import com.beamburst.casswatch.utils.WatchSyncActivity
import com.beamburst.casswatch.utils.AppScopes
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.beamburst.casswatch.data.local.WatchConnectionSession
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
    private val session: WatchConnectionSession,
    private val connectCoordinator: WatchConnectCoordinator,
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

    suspend fun applyTodaySchedule() {
        WatchSyncActivity.track {
        val ready = session.ready ?: return
        val watchTime = watchTimeProvider.nowForWatch(ready.address)
        try {
        connectCoordinator.withSession(ready) { checkSession ->
        val api = this.api.guardWrites(checkSession)
        val addr = ready.address
        WatchSettingsCache.reconcileOnConnect(context, addr, api, checkSession)

        val autoTimeSyncEnabled = LocalDataStorage.getOrInitializeAutoSyncOnReconnect(
            context = context,
            address = addr,
            seed = WatchSettingsCache.load(context, addr)?.timeAdjustment,
        )
        if (autoTimeSyncEnabled) {
            runAutoTimeSyncOnReconnect(ready, watchTime, api, checkSession)
        }

        val alarmDays = AlarmSyncStorage.loadDaySelections(context, addr)
        val storedAlarms = AlarmSyncStorage.loadAlarms(context, addr)
        val firedAts = AlarmSyncStorage.loadFiredAts(context, addr)
        val shouldWrite = AlarmSyncStorage.isDirty(context, addr) || !storedAlarms.isNullOrEmpty()

        if (!shouldWrite) return@withSession

        val currentAlarms = api.getAlarms()

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
                checkSession()
                api.setAlarms(ArrayList(alarmsToSend))
                if (WatchInfo.chimeInSettings) {
                    checkSession()
                    applyHourlySignalToWatch(api.guardWrites(checkSession), signalSettings, now)
                }
            }
            confirmWatchWrite(checkSession)
            syncLogger.record(
                address = addr,
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
                confirmWatchWrite(checkSession)
                AlarmSyncStorage.saveAlarms(context, addr, desiredAlarms, dirty = false, firedAts = firedAts)
            }
        } else {
            confirmWatchWrite(checkSession)
            syncLogger.record(
                address = addr,
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
                confirmWatchWrite(checkSession)
                AlarmSyncStorage.saveAlarms(context, addr, desiredAlarms, dirty = false, firedAts = firedAts)
            }
        }
        }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Reconnect synchronization failed for %s", ready.address)
            syncLogger.record(
                address = ready.address,
                trigger = SyncTrigger.AUTO_ON_RECONNECT,
                kinds = setOf(SyncKind.SETTINGS, SyncKind.ALARMS),
                success = false,
                errorMessage = error.message,
            )
        }
        }
    }

    private suspend fun runAutoTimeSyncOnReconnect(
        ready: WatchConnectionSession.Ready,
        watchTime: com.beamburst.casswatch.utils.WatchTime,
        api: org.avmedia.gshockapi.IGShockAPI,
        checkSession: () -> Unit,
    ) {
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

        checkSession()
        var writeResult: com.beamburst.casswatch.utils.TimeWriteResult? = null
        val result = runCatching {
            writeResult = watchTimeWriter.writeAfterPrime(api, watchTime, ready, checkSession)
        }
        confirmWatchWrite(checkSession)
        syncLogger.record(
            address = ready.address,
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
