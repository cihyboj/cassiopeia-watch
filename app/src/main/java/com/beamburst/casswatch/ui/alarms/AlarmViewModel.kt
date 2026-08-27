package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.utils.WatchSyncActivity
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
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import com.beamburst.casswatch.data.local.WatchSettingsCache
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.scratchpad.AlarmNameStorage
import org.avmedia.gshockapi.Alarm
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import timber.log.Timber
import org.avmedia.gshockapi.WatchInfo
import com.beamburst.casswatch.ui.common.AppSnackbar
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import com.beamburst.casswatch.utils.LocalDataStorage
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
)

data class HourlySignalUiState(
    val enabled: Boolean = false,
    val startHour: Int = 7,    // ∈ {1, 7, 13, 19}
    val endHour: Int = 18,     // ∈ {6, 12, 18, 0}
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
)

sealed class HourlySignalAction {
    data class SetEnabled(val on: Boolean) : HourlySignalAction()
    data class SetWindow(val startHour: Int, val endHour: Int) : HourlySignalAction()
    data class SetDays(val days: Set<DayOfWeek>) : HourlySignalAction()
}

fun alarmHash(
    hour: Int,
    minute: Int,
    days: Set<DayOfWeek>,
    enabled: Boolean
): String {
    return "%02d:%02d:%s".format(hour, minute, enabled)
}

internal fun alarmHashesForWatchPayload(alarms: List<Alarm>): List<String> =
    alarms.map { alarm -> alarmHash(alarm.hour, alarm.minute, emptySet(), alarm.enabled) }

internal val alarmSyncHistoryKinds = setOf(SyncKind.ALARMS, SyncKind.HOURLY_SIGNAL)

internal fun alarmSyncDetailsForWatchPayload(
    alarms: List<Alarm>,
    hourlySignalTarget: Boolean,
    actions: List<String>? = null,
): SyncDetails = SyncDetails(
    alarmHashes = alarmHashesForWatchPayload(alarms),
    eventCount = null,
    hourlySignalTarget = hourlySignalTarget,
    timeMs = null,
    actions = actions,
)

@HiltViewModel
class AlarmViewModel @Inject constructor(
    private val api: GShockRepository,
    private val alarmNameStorage: AlarmNameStorage,
    @param:ApplicationContext private val appContext: Context,
    private val syncLogger: SyncHistoryLogger,
    private val store: SyncHistoryStore,
    private val watchSelector: CurrentWatchSelector,
    private val connectionTracker: WatchConnectionTracker,
    private val phoneFallbackScheduler: PhoneFallbackScheduler,
) : ViewModel() {

    companion object {
        private const val DEFAULT_LOCAL_ALARM_COUNT = 5
    }

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private val _viewMode = MutableStateFlow(AlarmViewMode.WEEKLY)
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

    private val _automaticSyncEnabled = MutableStateFlow(true)
    val automaticSyncEnabled: StateFlow<Boolean> = _automaticSyncEnabled.asStateFlow()

    private val _syncWarnings = MutableStateFlow<Map<Int, String>>(emptyMap())
    val syncWarnings: StateFlow<Map<Int, String>> = _syncWarnings.asStateFlow()

    private var exactAlarmPermissionRequested = false
    private val eventSubscriberName = "AlarmViewModel-${System.identityHashCode(this)}"
    private val warningTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val initializedHourlySignalAddresses = mutableSetOf<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val lastAlarmSync: StateFlow<SyncEntry?> = watchSelector.selected
        .flatMapLatest { addr ->
            if (addr != null) {
                store.observe(addr)
                    .map { entries -> entries.firstOrNull { SyncKind.ALARMS in it.kinds && it.success } }
            } else {
                flowOf(null)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
            watchSelector.selected.collect { addr ->
                loadLocalStateForAddress(addr)
                loadAlarms()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            lastAlarmSync.collect {
                refreshSyncRiskState()
            }
        }
        startFireTimeTick()
    }

    private fun loadLocalStateForAddress(addr: String?) {
        _viewMode.value = AlarmViewMode.WEEKLY
        _alarmDays.value = if (addr != null) AlarmSyncStorage.loadDaySelections(appContext, addr) else emptyMap()
        _firedAts.value = if (addr != null) AlarmSyncStorage.loadFiredAts(appContext, addr) else emptyMap()
        _isConnected.value = isSelectedWatchConnected(addr)
        refreshAutomaticSyncEnabled(addr)

        if (addr != null) {
            AlarmSyncStorage.loadAlarms(appContext, addr)?.let {
                _alarms.value = it
            }
        }

        val signalSettings = loadHourlySignalSettings(addr)
        _hourlySignal.value = HourlySignalUiState(
            enabled   = signalSettings.enabled,
            startHour = signalSettings.startHour,
            endHour   = signalSettings.endHour,
            days      = signalSettings.days,
        )
        refreshSyncRiskState()
    }

    fun refreshAutomaticSyncEnabled(addr: String? = watchSelector.currentAddress()) {
        _automaticSyncEnabled.value = addr
            ?.let { WatchSettingsCache.load(appContext, it)?.timeAdjustment }
            ?: true
    }

    private fun loadHourlySignalSettings(addr: String?): HourlySignalSettings {
        if (addr == null) return HourlySignalSettings.Default
        if (HourlySignalSettings.hasStoredSettings(appContext, addr)) {
            initializedHourlySignalAddresses += addr
            return HourlySignalSettings.load(appContext, addr)
        }

        if (!HourlySignalSettings.isMigrated(appContext, addr) && AlarmSyncStorage.hasStoredAlarms(appContext, addr)) {
            val legacyChime = AlarmSyncStorage.loadAlarms(appContext, addr)
                ?.getOrNull(0)?.hasHourlyChime ?: false
            val initial = hourlySignalSettingsFromChime(legacyChime)
            HourlySignalSettings.save(appContext, addr, initial)
            HourlySignalSettings.markMigrated(appContext, addr)
            initializedHourlySignalAddresses += addr
            return initial
        }

        return HourlySignalSettings.Default
    }

    private fun hourlySignalSettingsFromChime(chimeOn: Boolean): HourlySignalSettings =
        if (chimeOn) HourlySignalSettings(enabled = true, startHour = 1, endHour = 0)
        else HourlySignalSettings.Default

    private fun seedHourlySignalSettingsFromWatch(addr: String?, chimeOn: Boolean) {
        if (addr == null ||
            addr in initializedHourlySignalAddresses ||
            HourlySignalSettings.hasStoredSettings(appContext, addr)
        ) {
            return
        }
        val initial = hourlySignalSettingsFromChime(chimeOn)
        HourlySignalSettings.save(appContext, addr, initial)
        HourlySignalSettings.markMigrated(appContext, addr)
        initializedHourlySignalAddresses += addr
        _hourlySignal.value = HourlySignalUiState(
            enabled = initial.enabled,
            startHour = initial.startHour,
            endHour = initial.endHour,
            days = initial.days,
        )
    }

    override fun onCleared() {
        ProgressEvents.subscriber.stop(eventSubscriberName)
        super.onCleared()
    }

    private fun startFireTimeTick() = viewModelScope.launch {
        while (true) {
            delay(60_000)
            runCatching { tickFireOnceAlarms() }
                .onFailure { Timber.w(it, "Fire-once alarm tick failed") }
        }
    }

    private fun tickFireOnceAlarms() {
        val addr = watchSelector.currentAddress() ?: return
        val now = System.currentTimeMillis()
        val nowTime = LocalTime.now()
        var anyFired = false

        val updatedFiredAts = _firedAts.value.toMutableMap()
        _alarms.value.forEachIndexed { index, alarm ->
            val isFireOnce = alarm.enabled &&
                _alarmDays.value[index]?.isEmpty() == true &&
                !updatedFiredAts.containsKey(index)
            if (isFireOnce && nowTime.isAfter(LocalTime.of(alarm.hour, alarm.minute))) {
                updatedFiredAts[index] = now
                anyFired = true
            }
        }

        if (anyFired) {
            _firedAts.value = updatedFiredAts
            AlarmSyncStorage.saveAlarms(appContext, addr, _alarms.value, dirty = true, firedAts = updatedFiredAts)
            refreshSyncRiskState(isDirtyOverride = true)
        }
    }

    private suspend fun hasLocalAppAlarms(localDraft: List<Alarm>?, addr: String?): Boolean {
        if (localDraft.isNullOrEmpty()) return false
        if (localDraft.any { it.enabled || it.hour != 0 || it.minute != 0 || !it.name.isNullOrBlank() || it.hasHourlyChime }) {
            return true
        }
        val days = if (addr != null) AlarmSyncStorage.loadDaySelections(appContext, addr) else emptyMap()
        return days.values.any { it.isNotEmpty() }
    }

    private fun loadAlarms() = viewModelScope.launch {
        runCatching {
            val addr = watchSelector.currentAddress()
            val localDraft = if (addr != null) AlarmSyncStorage.loadAlarms(appContext, addr) else null
            val connected = isSelectedWatchConnected(addr)
            if (!connected) {
                _alarms.value = localDraft ?: createDefaultLocalAlarms()
                return@runCatching
            }

            if (hasLocalAppAlarms(localDraft, addr)) {
                _alarms.value = localDraft ?: createDefaultLocalAlarms()
                return@runCatching
            }

            if (addr != null && AlarmSyncStorage.isDirty(appContext, addr) && !localDraft.isNullOrEmpty()) {
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
            seedHourlySignalSettingsFromWatch(addr, newAlarms.getOrNull(0)?.hasHourlyChime ?: false)
            _alarms.value = newAlarms
            if (addr != null) AlarmSyncStorage.saveAlarms(appContext, addr, newAlarms, dirty = false)
            ProgressEvents.onNext("Alarms Loaded")
        }.onFailure {
            val addr = watchSelector.currentAddress()
            _alarms.value = if (addr != null) AlarmSyncStorage.loadAlarms(appContext, addr) ?: createDefaultLocalAlarms()
                            else createDefaultLocalAlarms()
            ProgressEvents.onNext("Error")
        }
        refreshSyncRiskState()
    }

    private suspend fun refreshConnectionState() {
        _isConnected.value = isSelectedWatchConnected(watchSelector.currentAddress())
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
        val addr = watchSelector.currentAddress() ?: return
        AlarmSyncStorage.saveAlarms(appContext, addr, updated, dirty = true, firedAts = _firedAts.value)
    }

    fun toggleAlarm(index: Int, isEnabled: Boolean) {
        updateAlarm(index) { it.copy(enabled = isEnabled) }
        refreshSyncRiskState(isDirtyOverride = true)
    }

    fun onTimeChanged(index: Int, hours: Int, minutes: Int) {
        updateAlarm(index) { it.copy(hour = hours, minute = minutes, name = null) }
        refreshSyncRiskState(isDirtyOverride = true)
    }

    fun onHourlySignalAction(action: HourlySignalAction) {
        when (action) {
            is HourlySignalAction.SetEnabled -> {
                val updated = _hourlySignal.value.copy(enabled = action.on)
                _hourlySignal.value = updated
                saveSignalSettings(updated)
            }
            is HourlySignalAction.SetWindow -> {
                val updated = _hourlySignal.value.copy(
                    startHour = action.startHour,
                    endHour = action.endHour,
                )
                _hourlySignal.value = updated
                saveSignalSettings(updated)
            }
            is HourlySignalAction.SetDays -> {
                val wasEmpty = _hourlySignal.value.days.isEmpty()
                val newDays = action.days
                val updated = _hourlySignal.value.copy(
                    days = newDays,
                    enabled = when {
                        newDays.isEmpty() -> false
                        wasEmpty && newDays.isNotEmpty() -> true
                        else -> _hourlySignal.value.enabled
                    },
                )
                _hourlySignal.value = updated
                saveSignalSettings(updated)
            }
        }
    }

    private fun saveSignalSettings(state: HourlySignalUiState) {
        val addr = watchSelector.currentAddress() ?: return
        HourlySignalSettings.save(
            appContext,
            addr,
            HourlySignalSettings(
                enabled   = state.enabled,
                startHour = state.startHour,
                endHour   = state.endHour,
                days      = state.days,
            )
        )
        HourlySignalSettings.markMigrated(appContext, addr)
        initializedHourlySignalAddresses += addr
        refreshSyncRiskState(isDirtyOverride = true)
    }

    fun sendHourlySignalToWatch() = viewModelScope.launch {
        val addr = watchSelector.currentAddress()
        if (!api.isConnected()) {
            AppSnackbar(appContext.getString(R.string.watch_not_connected))
            return@launch
        }

        val settings = if (addr != null) {
            HourlySignalSettings.load(appContext, addr)
        } else {
            HourlySignalSettings.Default
        }
        val syncTime = LocalDateTime.now()
        val hourlySignalTarget = HourlySignalEvaluator.shouldChimeAt(syncTime, settings)
        val result = runCatching {
            applyHourlySignalToWatch(api, settings, syncTime)
        }
        syncLogger.record(
            trigger = SyncTrigger.MANUAL_SEND_ALARMS,
            kinds = setOf(SyncKind.HOURLY_SIGNAL),
            success = result.isSuccess,
            errorMessage = result.exceptionOrNull()?.message,
            details = alarmSyncDetailsForWatchPayload(
                alarms = emptyList(),
                hourlySignalTarget = hourlySignalTarget,
                actions = listOf("Hourly signal sent from Alarms screen"),
            ),
        )
        if (result.isSuccess) {
            AppSnackbar(appContext.getString(R.string.hourly_signal_sent_to_watch))
        } else {
            ProgressEvents.onNext("Error", result.exceptionOrNull()?.message ?: "")
        }
    }

    fun setViewMode(mode: AlarmViewMode) {
        _viewMode.value = AlarmViewMode.WEEKLY
        val addr = watchSelector.currentAddress() ?: return
        AlarmSyncStorage.saveViewMode(appContext, addr, AlarmViewMode.WEEKLY)
        AlarmSyncStorage.saveAlarms(appContext, addr, _alarms.value, dirty = true, firedAts = _firedAts.value)
        refreshSyncRiskState(isDirtyOverride = true)
    }

    fun toggleDay(alarmIndex: Int, day: DayOfWeek) {
        _alarmDays.update { current ->
            val days = current[alarmIndex] ?: emptySet()
            val updated = if (day in days) days - day else days + day
            current + (alarmIndex to updated)
        }
        val addr = watchSelector.currentAddress() ?: return
        AlarmSyncStorage.saveDaySelections(appContext, addr, _alarmDays.value)
        AlarmSyncStorage.saveAlarms(appContext, addr, _alarms.value, dirty = true, firedAts = _firedAts.value)
        refreshSyncRiskState(isDirtyOverride = true)
    }

    fun sendAlarmsToWatch() = viewModelScope.launch {
        WatchSyncActivity.track {
        val addr = watchSelector.currentAddress()
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

        // save() is a BLE write; a throw here must be reported like any other sync failure
        // instead of crashing the coroutine.
        runCatching { alarmNameStorage.save() }
            .onFailure {
                Timber.w(it, "Saving alarm names to watch failed")
                ProgressEvents.onNext("Error", it.message ?: "")
                return@launch
            }
        if (addr != null) AlarmSyncStorage.saveAlarms(appContext, addr, desiredAlarms, dirty = true, firedAts = currentFiredAts)

        val alarmsToSend = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = desiredAlarms,
            alarmDays = _alarmDays.value,
            now = LocalDateTime.now(),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = currentFiredAts
        )

        val signalSettings = if (addr != null) {
            HourlySignalSettings.load(appContext, addr)
        } else {
            HourlySignalSettings.Default
        }
        val syncTime = LocalDateTime.now()
        val hourlySignalTarget = HourlySignalEvaluator.shouldChimeAt(syncTime, signalSettings)

        val result = runCatching {
            api.getAlarms()
            val alarmsWithChime = AlarmSchedulePlanner.mirrorChime(alarmsToSend, hourlySignalTarget)
            api.setAlarms(ArrayList(alarmsWithChime))
            if (WatchInfo.chimeInSettings) {
                applyHourlySignalToWatch(api, signalSettings, syncTime)
            }

            val updatedFiredAts = currentFiredAts.toMutableMap()
            desiredAlarms.forEachIndexed { index, _ ->
                if (currentFiredAts.containsKey(index) && !alarmsToSend[index].enabled) {
                    updatedFiredAts.remove(index)
                    updateAlarm(index) { it.copy(enabled = false) }
                }
            }
            _firedAts.value = updatedFiredAts
            if (addr != null) AlarmSyncStorage.saveAlarms(appContext, addr, _alarms.value, dirty = false, firedAts = updatedFiredAts)
        }
        syncLogger.record(
            trigger = SyncTrigger.MANUAL_SEND_ALARMS,
            kinds = alarmSyncHistoryKinds,
            success = result.isSuccess,
            errorMessage = result.exceptionOrNull()?.message,
            details = alarmSyncDetailsForWatchPayload(
                alarms = alarmsToSend,
                hourlySignalTarget = hourlySignalTarget,
                actions = listOf("Alarms sent from Alarms screen"),
            ),
        )
        if (result.isSuccess) {
            refreshSyncRiskState(isDirtyOverride = false)
            AppSnackbar(appContext.getString(R.string.alarms_set_to_watch))
        } else {
            ProgressEvents.onNext("Error", result.exceptionOrNull()?.message ?: "")
        }
        }
    }

    fun openEditor(index: Int) {
        val alarm = _alarms.value.getOrNull(index) ?: return
        _editorTarget.value = AlarmDraft(
            index = index,
            hour = alarm.hour,
            minute = alarm.minute,
            name = alarm.name ?: "",
            days = _alarmDays.value[index] ?: AlarmSchedulePlanner.AllDays,
        )
    }

    fun dismissEditor() {
        _editorTarget.value = null
    }

    fun upsertAlarm(index: Int, hour: Int, minute: Int, name: String, days: Set<DayOfWeek>) {
        val isFireOnce = days.isEmpty()
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
        val addr = watchSelector.currentAddress() ?: return
        AlarmSyncStorage.saveDaySelections(appContext, addr, _alarmDays.value)
        refreshSyncRiskState(isDirtyOverride = true)
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
                    val days = when {
                        selectedDays == null -> allDays
                        selectedDays.isEmpty() -> null
                        else -> ArrayList(selectedDays.map { it.toCalendarDay() })
                    }

                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_MESSAGE, alarm.name)
                        putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
                        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                        if (days != null) putExtra(AlarmClock.EXTRA_DAYS, days)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    api.preventReconnection()
                    // Some ROMs ship no clock app that handles ACTION_SET_ALARM.
                    runCatching { appContext.startActivity(intent) }
                        .onFailure {
                            Timber.w(it, "No activity found to set phone alarm")
                            AppSnackbar(appContext.getString(R.string.no_clock_app_found))
                            return@launch
                        }
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

    private fun isSelectedWatchConnected(addr: String?): Boolean {
        if (addr == null || !api.isConnected()) return false
        val tracked = connectionTracker.states.value[addr]
        return tracked == ConnectionState.CONNECTED || tracked == null
    }

    private fun refreshSyncRiskState(isDirtyOverride: Boolean? = null) = viewModelScope.launch(Dispatchers.IO) {
        val addr = watchSelector.currentAddress()
        if (addr == null) {
            _syncWarnings.value = emptyMap()
            phoneFallbackScheduler.cancelAll(_alarms.value.indices.toList())
            return@launch
        }

        val settings = WatchSettingsCache.load(appContext, addr)
        val autoSyncEnabled = LocalDataStorage.getOrInitializeAutoSyncOnReconnect(
            context = appContext,
            address = addr,
            seed = settings?.timeAdjustment,
        )
        val adjustmentTimeMinutes = settings?.adjustmentTimeMinutes ?: 0
        val isDirty = isDirtyOverride ?: AlarmSyncStorage.isDirty(appContext, addr)
        val syncedHashes = lastAlarmSync.value?.details?.alarmHashes.orEmpty()
        val now = LocalDateTime.now()
        val warnings = mutableMapOf<Int, String>()

        _alarms.value.forEachIndexed { index, alarm ->
            val risk = AlarmSyncRiskEvaluator.evaluate(
                alarm = alarm,
                alarmIndex = index,
                alarmDays = _alarmDays.value[index] ?: AlarmSchedulePlanner.AllDays,
                viewMode = AlarmViewMode.WEEKLY,
                firedAts = _firedAts.value,
                isDirty = isDirty,
                syncedHashes = syncedHashes,
                now = now,
                autoSyncEnabled = autoSyncEnabled,
                adjustmentTimeMinutes = adjustmentTimeMinutes,
            )

            if (risk == null) {
                phoneFallbackScheduler.cancel(index)
            } else {
                warnings[index] = appContext.getString(
                    if (risk.nextAutoSyncAt == null) {
                        R.string.alarm_sync_risk_auto_off
                    } else {
                        R.string.alarm_sync_risk_before_auto_sync
                    },
                    risk.nextAlarmAt.format(warningTimeFormatter)
                )
                if (phoneFallbackScheduler.canScheduleExactAlarms()) {
                    phoneFallbackScheduler.schedule(
                        alarmIndex = index,
                        expectedHash = alarmHash(alarm.hour, alarm.minute, emptySet(), enabled = true),
                        fireInstant = risk.nextAlarmAt
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli(),
                        alarmHour = alarm.hour,
                        alarmMinute = alarm.minute,
                        alarmName = alarm.name,
                        deviceAddress = addr,
                    )
                } else {
                    phoneFallbackScheduler.cancel(index)
                }
            }
        }

        _syncWarnings.value = warnings
    }
}
