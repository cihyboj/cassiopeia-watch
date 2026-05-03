package com.beamburst.casswatch.ui.alarms

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncHistoryStore
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.scratchpad.AlarmNameStorage
import com.beamburst.casswatch.utils.Utils
import org.avmedia.gshockapi.Alarm
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import com.beamburst.casswatch.ui.common.AppSnackbar
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import com.beamburst.casswatch.utils.SyncHistoryLogger
import javax.inject.Inject

enum class AlarmViewMode { SIMPLE, WEEKLY }

/**
 * Represents one-time UI events that should be handled by the UI layer.
 */
sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    object RequestExactAlarmPermission : UiEvent()
}

data class AlarmDraft(
    val index: Int,
    val hour: Int,
    val minute: Int,
    val name: String,
    val days: Set<DayOfWeek>,
    val viewMode: AlarmViewMode
)

data class HourlySignalUiState(
    val enabled: Boolean = false,
    val startHour: Int = 7,
    val endHour: Int = 18,
    val showDialog: Boolean = false,
)

sealed class HourlySignalAction {
    data class SetEnabled(val on: Boolean) : HourlySignalAction()
    data object OpenDialog : HourlySignalAction()
    data class SaveWindow(val startHour: Int, val endHour: Int) : HourlySignalAction()
    data object DismissDialog : HourlySignalAction()
}

fun alarmHash(
    hour: Int,
    minute: Int,
    days: Set<DayOfWeek>,
    enabled: Boolean
): String {
    val dayMask = if (days.isEmpty()) "ALL" else days.sorted().joinToString(",") { it.name.take(3) }
    return "%02d:%02d:%s:%s".format(hour, minute, dayMask, enabled)
}

@HiltViewModel
class AlarmViewModel @Inject constructor(
    private val api: GShockRepository,
    private val alarmNameStorage: AlarmNameStorage,
    @param:ApplicationContext private val appContext: Context,
    private val syncLogger: SyncHistoryLogger,
    private val store: SyncHistoryStore,
    private val watchSelector: CurrentWatchSelector,
) : ViewModel() {

    companion object {
        const val ALARM_VIEW_MODE_KEY = "AlarmViewMode"
        const val ALARM_DAYS_KEY = "AlarmDaySelections"
        private const val DEFAULT_LOCAL_ALARM_COUNT = 5
    }

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private val _viewMode = MutableStateFlow(AlarmViewMode.SIMPLE)
    val viewMode: StateFlow<AlarmViewMode> = _viewMode.asStateFlow()

    private val _alarmDays = MutableStateFlow<Map<Int, Set<DayOfWeek>>>(emptyMap())
    val alarmDays: StateFlow<Map<Int, Set<DayOfWeek>>> = _alarmDays.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _firedAts = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val firedAts: StateFlow<Map<Int, Long>> = _firedAts.asStateFlow()

    private val _editorTarget = MutableStateFlow<AlarmDraft?>(null)
    val editorTarget: StateFlow<AlarmDraft?> = _editorTarget.asStateFlow()

    private val _hourlySignal = MutableStateFlow(HourlySignalUiState())
    val hourlySignal: StateFlow<HourlySignalUiState> = _hourlySignal.asStateFlow()

    private var exactAlarmPermissionRequested = false
    private val eventSubscriberName = "AlarmViewModel-${System.identityHashCode(this)}"
    val lastAlarmSync: StateFlow<SyncEntry?> = run {
        val addr = watchSelector.currentAddress()
        if (addr != null) {
            store.observe(addr)
                .map { entries -> entries.firstOrNull { SyncKind.ALARMS in it.kinds && it.success } }
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)
        } else {
            MutableStateFlow(null)
        }
    }

    init {
        ProgressEvents.runEventActions(
            eventSubscriberName,
            arrayOf(
                EventAction("DeviceAppeared") {
                    viewModelScope.launch {
                        refreshConnectionState()
                        if (_isConnected.value) loadAlarms()
                    }
                },
                EventAction("ConnectionSetupComplete") {
                    viewModelScope.launch {
                        _isConnected.value = true
                        loadAlarms()
                    }
                },
                EventAction("WatchInitializationCompleted") {
                    viewModelScope.launch {
                        _isConnected.value = true
                        loadAlarms()
                    }
                },
                EventAction("DeviceDisappeared") { _isConnected.value = false },
                EventAction("Disconnect") { _isConnected.value = false },
                EventAction("ConnectionFailed") { _isConnected.value = false }
            )
        )

        viewModelScope.launch(Dispatchers.IO) {
            _viewMode.value = AlarmSyncStorage.loadViewMode(appContext)
            _alarmDays.value = AlarmSyncStorage.loadDaySelections(appContext)
            _firedAts.value = AlarmSyncStorage.loadFiredAts(appContext)
            _isConnected.value = api.isConnected()

            AlarmSyncStorage.loadAlarms(appContext)?.let {
                _alarms.value = it
            }

            val signalSettings = if (!HourlySignalSettings.isMigrated(appContext)) {
                val legacyChime = AlarmSyncStorage.loadAlarms(appContext)
                    ?.getOrNull(0)?.hasHourlyChime ?: false
                val initial = if (legacyChime) {
                    HourlySignalSettings(enabled = true, startHour = 1, endHour = 0)
                } else {
                    HourlySignalSettings.Default
                }
                HourlySignalSettings.save(appContext, initial)
                HourlySignalSettings.markMigrated(appContext)
                initial
            } else {
                HourlySignalSettings.load(appContext)
            }
            _hourlySignal.value = HourlySignalUiState(
                enabled   = signalSettings.enabled,
                startHour = signalSettings.startHour,
                endHour   = signalSettings.endHour,
            )
        }
        loadAlarms()
        startFireTimeTick()
    }

    override fun onCleared() {
        ProgressEvents.subscriber.stop(eventSubscriberName)
        super.onCleared()
    }

    private fun startFireTimeTick() = viewModelScope.launch {
        while (true) {
            delay(60_000)
            tickFireOnceAlarms()
        }
    }

    private fun tickFireOnceAlarms() {
        if (_viewMode.value != AlarmViewMode.WEEKLY) return
        val now = System.currentTimeMillis()
        val nowTime = LocalTime.now()
        var anyFired = false

        val updatedFiredAts = _firedAts.value.toMutableMap()
        _alarms.value.forEachIndexed { index, alarm ->
            val isFireOnce = alarm.enabled &&
                _alarmDays.value[index].isNullOrEmpty() &&
                !updatedFiredAts.containsKey(index)
            if (isFireOnce && nowTime.isAfter(LocalTime.of(alarm.hour, alarm.minute))) {
                updatedFiredAts[index] = now
                anyFired = true
            }
        }

        if (anyFired) {
            _firedAts.value = updatedFiredAts
            AlarmSyncStorage.saveAlarms(appContext, _alarms.value, dirty = true, firedAts = updatedFiredAts)
        }
    }

    private suspend fun hasLocalAppAlarms(localDraft: List<Alarm>?): Boolean {
        if (localDraft.isNullOrEmpty()) return false
        if (localDraft.any { it.enabled || it.hour != 0 || it.minute != 0 || !it.name.isNullOrBlank() || it.hasHourlyChime }) {
            return true
        }
        val days = AlarmSyncStorage.loadDaySelections(appContext)
        return days.values.any { it.isNotEmpty() }
    }

    private fun loadAlarms() = viewModelScope.launch {
        runCatching {
            val localDraft = AlarmSyncStorage.loadAlarms(appContext)
            val connected = api.isConnected()
            if (!connected) {
                _alarms.value = localDraft ?: createDefaultLocalAlarms()
                return@runCatching
            }

            if (hasLocalAppAlarms(localDraft)) {
                _alarms.value = localDraft ?: createDefaultLocalAlarms()
                return@runCatching
            }

            if (AlarmSyncStorage.isDirty(appContext) && !localDraft.isNullOrEmpty()) {
                _alarms.value = localDraft
                return@runCatching
            }

            alarmNameStorage.load()

            val alarmsFromWatch = api.getAlarms()
                .take(WatchInfo.alarmCount)
                .mapIndexed { index, alarm ->
                    val name = alarmNameStorage.get(index)
                    alarm.copy(name = name)
                }

            val newAlarms = if (WatchInfo.chimeInSettings) {
                val settings = api.getSettings()
                alarmsFromWatch.mapIndexed { index, alarm ->
                    if (index == 0) alarm.copy(hasHourlyChime = settings.hourlyChime)
                    else alarm
                }
            } else {
                alarmsFromWatch
            }
            _alarms.value = newAlarms
            AlarmSyncStorage.saveAlarms(appContext, newAlarms, dirty = false)
            ProgressEvents.onNext("Alarms Loaded")
        }.onFailure {
            _alarms.value = AlarmSyncStorage.loadAlarms(appContext)
                ?: createDefaultLocalAlarms()
            ProgressEvents.onNext("Error")
        }
    }

    private suspend fun refreshConnectionState() {
        _isConnected.value = api.isConnected()
    }

    private fun createDefaultLocalAlarms(): List<Alarm> {
        val count = WatchInfo.alarmCount.takeIf { it > 0 } ?: DEFAULT_LOCAL_ALARM_COUNT
        return List(count) { Alarm(0, 0, false, false, "") }
    }

    private fun updateAlarm(index: Int, transform: (Alarm) -> Alarm) {
        val updated = _alarms.value.mapIndexed { i, alarm ->
            if (i == index) transform(alarm) else alarm
        }
        _alarms.value = updated
        AlarmSyncStorage.saveAlarms(appContext, updated, dirty = true, firedAts = _firedAts.value)
    }

    fun toggleAlarm(index: Int, isEnabled: Boolean) =
        updateAlarm(index) { it.copy(enabled = isEnabled) }

    fun onTimeChanged(index: Int, hours: Int, minutes: Int) {
        updateAlarm(index) { it.copy(hour = hours, minute = minutes, name = null) }
    }

    fun onHourlySignalAction(action: HourlySignalAction) {
        when (action) {
            is HourlySignalAction.SetEnabled -> {
                val updated = _hourlySignal.value.copy(enabled = action.on)
                _hourlySignal.value = updated
                HourlySignalSettings.save(
                    appContext,
                    HourlySignalSettings(
                        enabled   = updated.enabled,
                        startHour = updated.startHour,
                        endHour   = updated.endHour,
                    )
                )
            }
            is HourlySignalAction.OpenDialog ->
                _hourlySignal.value = _hourlySignal.value.copy(showDialog = true)
            is HourlySignalAction.SaveWindow -> {
                val updated = _hourlySignal.value.copy(
                    startHour  = action.startHour,
                    endHour    = action.endHour,
                    showDialog = false,
                )
                _hourlySignal.value = updated
                HourlySignalSettings.save(
                    appContext,
                    HourlySignalSettings(
                        enabled   = updated.enabled,
                        startHour = updated.startHour,
                        endHour   = updated.endHour,
                    )
                )
            }
            is HourlySignalAction.DismissDialog ->
                _hourlySignal.value = _hourlySignal.value.copy(showDialog = false)
        }
    }

    fun setViewMode(mode: AlarmViewMode) {
        _viewMode.value = mode
        AlarmSyncStorage.saveViewMode(appContext, mode)
        AlarmSyncStorage.saveAlarms(appContext, _alarms.value, dirty = true, firedAts = _firedAts.value)
    }

    fun toggleDay(alarmIndex: Int, day: DayOfWeek) {
        _alarmDays.update { current ->
            val days = current[alarmIndex] ?: emptySet()
            val updated = if (day in days) days - day else days + day
            current + (alarmIndex to updated)
        }
        saveDays()
        AlarmSyncStorage.saveAlarms(appContext, _alarms.value, dirty = true, firedAts = _firedAts.value)
    }

    private fun saveDays() {
        AlarmSyncStorage.saveDaySelections(appContext, _alarmDays.value)
    }

    fun sendAlarmsToWatch() = viewModelScope.launch {
        if (!api.isConnected()) {
            AppSnackbar(appContext.getString(R.string.watch_not_connected))
            return@launch
        }

        val currentFiredAts = _firedAts.value

        val desiredAlarms = _alarms.value.mapIndexed { index, alarm ->
            if (alarm.name == null) {
                alarmNameStorage.put("", index)
                alarm.copy(name = "")
            } else {
                alarm
            }
        }

        alarmNameStorage.save()
        AlarmSyncStorage.saveAlarms(appContext, desiredAlarms, dirty = true, firedAts = currentFiredAts)

        val alarmsToSend = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = desiredAlarms,
            alarmDays = _alarmDays.value,
            now = LocalDateTime.now(),
            viewMode = _viewMode.value,
            firedAts = currentFiredAts
        )

        val hashes = desiredAlarms.mapIndexed { index, alarm ->
            val days = _alarmDays.value[index] ?: emptySet()
            alarmHash(alarm.hour, alarm.minute, days, alarm.enabled)
        }

        val result = runCatching {
            api.getAlarms()
            val signalSettings = HourlySignalSettings.load(appContext)
            val chimeTarget = HourlySignalEvaluator.shouldChimeAt(LocalDateTime.now(), signalSettings)
            val alarmsWithChime = AlarmSchedulePlanner.mirrorChime(alarmsToSend, chimeTarget)
            api.setAlarms(ArrayList(alarmsWithChime))
            if (WatchInfo.chimeInSettings) {
                applyHourlySignalToWatch(api, signalSettings, LocalDateTime.now())
            }

            val updatedFiredAts = currentFiredAts.toMutableMap()
            desiredAlarms.forEachIndexed { index, _ ->
                if (currentFiredAts.containsKey(index) && !alarmsToSend[index].enabled) {
                    updatedFiredAts.remove(index)
                    updateAlarm(index) { it.copy(enabled = false) }
                }
            }
            _firedAts.value = updatedFiredAts
            AlarmSyncStorage.saveAlarms(appContext, _alarms.value, dirty = false, firedAts = updatedFiredAts)
        }
        syncLogger.record(
            trigger = SyncTrigger.MANUAL_SEND_ALARMS,
            kinds = setOf(SyncKind.ALARMS, SyncKind.HOURLY_SIGNAL),
            success = result.isSuccess,
            errorMessage = result.exceptionOrNull()?.message,
            details = SyncDetails(
                alarmHashes = hashes,
                eventCount = null,
                hourlySignalTarget = HourlySignalEvaluator.shouldChimeAt(
                    LocalDateTime.now(),
                    HourlySignalSettings.load(appContext)
                ),
                timeMs = null,
            ),
        )
        if (result.isSuccess) {
            AppSnackbar(appContext.getString(R.string.alarms_set_to_watch))
        } else {
            ProgressEvents.onNext("Error", result.exceptionOrNull()?.message ?: "")
        }
    }

    fun openEditor(index: Int) {
        val alarm = _alarms.value.getOrNull(index) ?: return
        _editorTarget.value = AlarmDraft(
            index = index,
            hour = alarm.hour,
            minute = alarm.minute,
            name = alarm.name ?: "",
            days = _alarmDays.value[index] ?: emptySet(),
            viewMode = _viewMode.value
        )
    }

    fun dismissEditor() {
        _editorTarget.value = null
    }

    fun upsertAlarm(index: Int, hour: Int, minute: Int, name: String, days: Set<DayOfWeek>) {
        val isFireOnce = _viewMode.value == AlarmViewMode.WEEKLY && days.isEmpty()
        if (isFireOnce && !exactAlarmPermissionRequested) {
            exactAlarmPermissionRequested = true
            viewModelScope.launch { _uiEvents.emit(UiEvent.RequestExactAlarmPermission) }
        }

        val existing = _alarms.value.getOrNull(index)
        val timeChanged = existing?.hour != hour || existing.minute != minute
        if (timeChanged) {
            val updated = _firedAts.value.toMutableMap().also { it.remove(index) }
            _firedAts.value = updated
        }

        updateAlarm(index) { it.copy(hour = hour, minute = minute, name = name.ifBlank { null }, enabled = true) }
        _alarmDays.update { it + (index to days) }
        AlarmSyncStorage.saveDaySelections(appContext, _alarmDays.value)
        _editorTarget.value = null
    }

    fun sendAlarmsToPhone() {
        val allDays = arrayListOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )

        viewModelScope.launch {
            _alarms.value
                .withIndex()
                .filter { it.value.enabled }
                .forEach { (index, alarm) ->
                    val selectedDays = _alarmDays.value[index]
                    val days = if (selectedDays.isNullOrEmpty()) allDays
                               else ArrayList(selectedDays.map { it.toCalendarDay() })

                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_MESSAGE, alarm.name)
                        putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
                        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                        putExtra(AlarmClock.EXTRA_DAYS, days)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    api.preventReconnection()
                    appContext.startActivity(intent)
                    delay(1000L)
                }
        }
    }

    private fun DayOfWeek.toCalendarDay(): Int = when (this) {
        DayOfWeek.MONDAY -> Calendar.MONDAY
        DayOfWeek.TUESDAY -> Calendar.TUESDAY
        DayOfWeek.WEDNESDAY -> Calendar.WEDNESDAY
        DayOfWeek.THURSDAY -> Calendar.THURSDAY
        DayOfWeek.FRIDAY -> Calendar.FRIDAY
        DayOfWeek.SATURDAY -> Calendar.SATURDAY
        DayOfWeek.SUNDAY -> Calendar.SUNDAY
    }
}
