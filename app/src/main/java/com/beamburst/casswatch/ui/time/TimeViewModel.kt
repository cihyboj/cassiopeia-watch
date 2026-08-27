package com.beamburst.casswatch.ui.time

import com.beamburst.casswatch.utils.WatchSyncActivity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import com.beamburst.casswatch.data.local.WatchSnapshot
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.ui.alarms.applyHourlySignalToWatch
import com.beamburst.casswatch.session.SessionLinkEvent
import com.beamburst.casswatch.session.WatchSessionUiBridge
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.SyncHistoryLogger
import com.beamburst.casswatch.utils.TimeWriteResult
import com.beamburst.casswatch.utils.Utils
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.utils.WatchTimeProvider
import com.beamburst.casswatch.utils.WatchTimeWriter
import com.beamburst.casswatch.utils.syncHistoryActions
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class TimeState(
    val homeTime: String = "",
    val batteryLevel: Int = 0,
    val temperature: Int = 0,
    val watchName: String = "",
    val isConnected: Boolean = false
)

data class ClockDisplaySource(
    val ntpTimeEnabled: Boolean = false,
    val ntpOffsetCache: com.beamburst.casswatch.utils.NtpOffsetCache? = null,
) {
    /**
     * Mirrors [com.beamburst.casswatch.ui.time.currentTimeMillisForDisplay] decision logic so
     * the badge in [LocalTimeView] reflects the source actually being used to render the
     * clock: NTP is shown only if it's enabled AND there's a fresh-enough cached offset.
     */
    fun isUsingNtp(elapsedNowMs: Long): Boolean {
        if (!ntpTimeEnabled) return false
        val cache = ntpOffsetCache ?: return false
        val ageMs = elapsedNowMs - cache.capturedElapsedRealtimeMs
        return ageMs in 0L..NTP_DISPLAY_CACHE_VALID_MS
    }

    companion object {
        const val NTP_DISPLAY_CACHE_VALID_MS = 30L * 60L * 1_000L
    }
}

sealed interface TimeAction {
    data object SendTimeToWatch : TimeAction
    data object RefreshState : TimeAction
}

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
}

@HiltViewModel
class TimeViewModel @Inject constructor(
    private val api: GShockRepository,
    private val watchSessionUiBridge: WatchSessionUiBridge,
    @param:ApplicationContext private val appContext: Context,
    private val syncLogger: SyncHistoryLogger,
    private val watchSelector: CurrentWatchSelector,
    private val connectionTracker: WatchConnectionTracker,
    private val watchTimeProvider: WatchTimeProvider,
    private val watchTimeWriter: WatchTimeWriter,
) : ViewModel() {
    private val _state = MutableStateFlow(TimeState())
    val state = _state.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()
    val clockDisplaySource: StateFlow<ClockDisplaySource> =
        combine(
            LocalDataStorage.ntpTimeEnabledFlow(appContext),
            LocalDataStorage.ntpOffsetCacheFlow(appContext),
        ) { enabled, cache ->
            ClockDisplaySource(
                ntpTimeEnabled = enabled,
                ntpOffsetCache = cache,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ClockDisplaySource(),
        )

    /**
     * Blocks [refreshState] from showing a connected snapshot while the API still reports
     * connected after a drop. Cleared by [WatchSessionUiBridge] (same path as snackbars),
     * [onWatchConnected], or a successful full read in [refreshState].
     */
    private val linkLossUiHoldActive = AtomicBoolean(false)

    /**
     * [Utils.AppHashCode] from an `init` block often resolves to the same key as other subscribers
     * (e.g. `"<init>"`.hashCode()), so ProgressEvents can overwrite this subscription — then
     * [Disconnect] never reaches the Time tab. Match [AlarmViewModel]'s unique subscriber id.
     */
    private val progressEventsSubscriberId = "TimeViewModel-${System.identityHashCode(this)}"

    init {
        ProgressEvents.runEventActions(
            progressEventsSubscriberId,
            arrayOf(
                EventAction("WatchInitializationCompleted") { onWatchConnected() },
                EventAction("ConnectionSetupComplete") { onWatchConnected() },
                EventAction("DeviceName") { refreshState() },
                EventAction("DeviceAddress") { refreshState() },
                EventAction("Disconnect") { onLinkLostEvent() },
                EventAction("DeviceDisappeared") { onLinkLostEvent() },
                EventAction("ConnectionFailed") { onLinkLostEvent() },
            )
        )
        viewModelScope.launch {
            watchSessionUiBridge.linkEvents.collect { event ->
                when (event) {
                    SessionLinkEvent.Disconnected -> {
                        linkLossUiHoldActive.set(true)
                        withContext(Dispatchers.IO) {
                            _state.value = TimeState(
                                watchName = resolveWatchName(),
                                isConnected = false,
                            )
                        }
                        refreshState()
                    }
                    SessionLinkEvent.Connected -> {
                        linkLossUiHoldActive.set(false)
                        refreshState()
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            watchSelector.selected.collect { addr ->
                loadCachedState(addr)
                if (isSelectedWatchConnected(addr)) refreshState()
            }
        }
    }

    override fun onCleared() {
        ProgressEvents.subscriber.stop(progressEventsSubscriberId)
        super.onCleared()
    }

    fun onAction(action: TimeAction) {
        when (action) {
            TimeAction.SendTimeToWatch -> {
                viewModelScope.launch {
                    WatchSyncActivity.track {
                    if (!api.isConnected()) {
                        AppSnackbar(appContext.getString(R.string.watch_not_connected))
                        return@launch
                    }
                    val watchTime = watchTimeProvider.nowForWatch()
                    watchTime.fallbackReason?.let { AppSnackbar(it) }
                    AppSnackbar(appContext.getString(R.string.sending_time_to_watch))
                    var writeResult: TimeWriteResult? = null
                    val result = runCatching {
                        writeResult = watchTimeWriter.write(api, watchTime)
                        val addr = watchSelector.currentAddress()
                        val signalSettings = if (addr != null) {
                            HourlySignalSettings.load(appContext, addr)
                        } else {
                            HourlySignalSettings.Default
                        }
                        applyHourlySignalToWatch(api, signalSettings, LocalDateTime.now())
                    }
                    syncLogger.record(
                        trigger = SyncTrigger.MANUAL_SET_TIME,
                        kinds = setOf(SyncKind.TIME),
                        success = result.isSuccess,
                        errorMessage = result.exceptionOrNull()?.message,
                        details = SyncDetails(
                        alarmHashes = null,
                        eventCount = null,
                        hourlySignalTarget = null,
                        timeMs = writeResult?.timeMs,
                        actions = watchTime.syncHistoryActions("Time screen send-time button", writeResult),
                    ),
                )
                    if (result.isSuccess) {
                        AppSnackbar(appContext.getString(R.string.time_set))
                        refreshState()
                    } else {
                        AppSnackbar(result.exceptionOrNull()?.message ?: "Api Error")
                    }
                    }
                }
            }

            TimeAction.RefreshState -> refreshState()
        }
    }

    private fun onLinkLostEvent() {
        watchSessionUiBridge.emitDisconnected()
    }

    private fun onWatchConnected() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!api.isConnected()) return@launch
            // End disconnect UI hold as soon as the API agrees we have a session, so refreshState
            // cannot keep forcing "disconnected" on every tick (second reconnect).
            linkLossUiHoldActive.set(false)
            runCatching {
                val homeTime = if (WatchInfo.hasHomeTime) api.getHomeTime() else ""
                val battery = api.getBatteryLevel()
                val temperature = api.getWatchTemperature()
                saveWatchSnapshot(battery, temperature, homeTime)
                _state.value = TimeState(
                    homeTime = homeTime,
                    batteryLevel = battery,
                    temperature = temperature,
                    watchName = resolveWatchName(),
                    isConnected = true
                )
            }.onFailure {
                AppSnackbar("Api Error")
                val connected = runCatching { api.isConnected() }.getOrDefault(false)
                _state.value = _state.value.copy(
                    watchName = resolveWatchName(),
                    isConnected = connected,
                )
            }
        }
    }

    private fun resolveWatchName(): String {
        return resolveWatchName(watchSelector.currentAddress())
    }

    private fun resolveWatchName(address: String?): String {
        if (api.isConnected() && isSelectedWatchConnected(address)) {
            val connectedName = WatchInfo.name
            if (!connectedName.isNullOrBlank()) return connectedName.removePrefix("CASIO").trim()
        }
        if (!address.isNullOrBlank()) {
            val perDevice = LocalDataStorage.getDeviceName(appContext, address)
            if (!perDevice.isNullOrBlank()) return perDevice.removePrefix("CASIO").trim()
        }
        val stored = LocalDataStorage.get(appContext, "LastDeviceName", "")
        if (!stored.isNullOrBlank()) return stored.removePrefix("CASIO").trim()
        return appContext.getString(R.string.no_watch)
    }

    private fun refreshState() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val selected = watchSelector.currentAddress()
                if (!isSelectedWatchConnected(selected)) {
                    loadCachedState(selected)
                    return@runCatching
                }

                val primedWatchName = resolveWatchName()
                _state.value = _state.value.copy(
                    watchName = primedWatchName,
                    isConnected = true,
                )

                val homeTime = if (WatchInfo.hasHomeTime) api.getHomeTime() else ""
                val battery = api.getBatteryLevel()
                val temperature = api.getWatchTemperature()
                saveWatchSnapshot(battery, temperature, homeTime)
                _state.value = TimeState(
                    homeTime = homeTime,
                    batteryLevel = battery,
                    temperature = temperature,
                    watchName = primedWatchName,
                    isConnected = true
                )
                linkLossUiHoldActive.set(false)
            }.onFailure {
                AppSnackbar("Api Error")
                val safeName = resolveWatchName()
                val connected = runCatching { api.isConnected() }.getOrDefault(false)
                _state.value = _state.value.copy(
                    watchName = safeName,
                    isConnected = connected,
                )
            }
        }
    }

    private fun loadCachedState(address: String?) {
        val snapshot = address?.let { WatchSnapshot.load(appContext, it) }
        _state.value = TimeState(
            homeTime = snapshot?.homeTime.orEmpty(),
            batteryLevel = snapshot?.battery ?: 0,
            temperature = snapshot?.temperature ?: 0,
            watchName = resolveWatchName(address),
            isConnected = false,
        )
    }

    private fun isSelectedWatchConnected(address: String?): Boolean {
        if (address == null || !api.isConnected()) return false
        val tracked = connectionTracker.states.value[address]
        return tracked == ConnectionState.CONNECTED || tracked == null
    }

    private fun saveWatchSnapshot(battery: Int, temperature: Int, homeTime: String) {
        val addr = watchSelector.currentAddress() ?: return
        val current = WatchSnapshot.load(appContext, addr)
        WatchSnapshot.save(
            appContext,
            addr,
            WatchSnapshot(
                timer = current?.timer,
                battery = battery,
                temperature = temperature,
                homeTime = homeTime.takeIf { it.isNotBlank() },
                cachedAt = System.currentTimeMillis(),
            )
        )
    }
}
