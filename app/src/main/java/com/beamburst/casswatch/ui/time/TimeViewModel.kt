package com.beamburst.casswatch.ui.time

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.ui.alarms.applyHourlySignalToWatch
import com.beamburst.casswatch.session.SessionLinkEvent
import com.beamburst.casswatch.session.WatchSessionUiBridge
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.SyncHistoryLogger
import com.beamburst.casswatch.utils.Utils
import com.beamburst.casswatch.ui.common.AppSnackbar
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class TimeState(
    val timer: Int = 0,
    val homeTime: String = "",
    val batteryLevel: Int = 0,
    val temperature: Int = 0,
    val watchName: String = "",
    val isConnected: Boolean = false
)

sealed interface TimeAction {
    data class SetTimer(val hours: Int, val minutes: Int, val seconds: Int) : TimeAction
    data class UpdateTimer(val timeMs: Int) : TimeAction
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
) : ViewModel() {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "TimeViewModel"
    }

    private val _state = MutableStateFlow(TimeState())
    val state = _state.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

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
        refreshState()
    }

    override fun onCleared() {
        ProgressEvents.subscriber.stop(EVENT_SUBSCRIBER_ID)
        super.onCleared()
    }

    fun onAction(action: TimeAction) {
        when (action) {
            is TimeAction.SetTimer -> {
                _state.value = _state.value.copy(
                    timer = action.hours * 3600 + action.minutes * 60 + action.seconds
                )
            }

            is TimeAction.UpdateTimer -> {
                viewModelScope.launch {
                    if (!api.isConnected()) {
                        AppSnackbar(appContext.getString(R.string.watch_not_connected))
                    } else {
                        api.setTimer(action.timeMs)
                        AppSnackbar(appContext.getString(R.string.timer_set))
                    }
                }
            }

            TimeAction.SendTimeToWatch -> {
                viewModelScope.launch {
                    if (!api.isConnected()) {
                        AppSnackbar(appContext.getString(R.string.watch_not_connected))
                        return@launch
                    }
                    val timeOffset = LocalDataStorage.getFineTimeAdjustment(appContext)
                    val timeMs = System.currentTimeMillis() + timeOffset
                    AppSnackbar(appContext.getString(R.string.sending_time_to_watch))
                    val result = runCatching {
                        api.setTime(timeMs = timeMs)
                        if (WatchInfo.chimeInSettings) {
                            val signalSettings = HourlySignalSettings.load(appContext)
                            applyHourlySignalToWatch(api, signalSettings, LocalDateTime.now())
                        }
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
                            timeMs = timeMs,
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
                _state.value = TimeState(
                    timer = api.getTimer(),
                    homeTime = if (WatchInfo.hasHomeTime) api.getHomeTime() else "",
                    batteryLevel = api.getBatteryLevel(),
                    temperature = api.getWatchTemperature(),
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
        if (api.isConnected()) {
            val connectedName = WatchInfo.name
            if (!connectedName.isNullOrBlank()) return connectedName.removePrefix("CASIO").trim()
        }
        val lastAddress = LocalDataStorage.get(appContext, "LastDeviceAddress", "")
        if (!lastAddress.isNullOrBlank()) {
            val perDevice = LocalDataStorage.getDeviceName(appContext, lastAddress)
            if (!perDevice.isNullOrBlank()) return perDevice.removePrefix("CASIO").trim()
        }
        val stored = LocalDataStorage.get(appContext, "LastDeviceName", "")
        if (!stored.isNullOrBlank()) return stored.removePrefix("CASIO").trim()
        return appContext.getString(R.string.no_watch)
    }

    private fun refreshState() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (!api.isConnected()) {
                    val wn = resolveWatchName()
                    if (!api.isConnected()) {
                        _state.value = TimeState(watchName = wn)
                        return@runCatching
                    }
                }

                if (!api.isConnected()) {
                    val wn = resolveWatchName()
                    _state.value = TimeState(watchName = wn)
                    return@runCatching
                }

                val primedWatchName = resolveWatchName()
                _state.value = _state.value.copy(
                    watchName = primedWatchName,
                    isConnected = true,
                )

                _state.value = TimeState(
                    timer = api.getTimer(),
                    homeTime = if (WatchInfo.hasHomeTime) api.getHomeTime() else "",
                    batteryLevel = api.getBatteryLevel(),
                    temperature = api.getWatchTemperature(),
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
}
