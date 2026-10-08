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
    val oneShotLocalDate: String? = null,
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
    private val alarmCoordinator: AlarmSchedulingCoordinator,
    private val alarmTime: AlarmSchedulingTime,
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

    private val _oneShotLocalDates = MutableStateFlow<Map<Int, String>>(emptyMap())
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
                if (addr != null) alarmCoordinator.reconcile(addr)
                loadLocalStateForAddress(addr)
                loadAlarms()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            lastAlarmSync.collect {
                refreshSyncRiskState()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            watchSelector.selected.flatMapLatest { addr ->
                if (addr == null) flowOf(null) else AlarmSyncStorage.stateFlowFor(appContext, addr)
            }.collect { state ->
                if (state != null && state.alarms.isNotEmpty()) {
                    _alarms.value = state.watchAlarms()
                    _alarmDays.value = state.days
                    _firedAts.value = state.firedAts()
                    _oneShotLocalDates.value = state.oneShotLocalDates()
                    refreshSyncRiskState()
                }
            }
        }
        startFireTimeTick()
    }

    private fun loadLocalStateForAddress(addr: String?) {
        _viewMode.value = AlarmViewMode.WEEKLY
        _alarmDays.value = if (addr != null) AlarmSyncStorage.loadDaySelections(appContext, addr) else emptyMap()
        _firedAts.value = if (addr != null) AlarmSyncStorage.loadFiredAts(appContext, addr) else emptyMap()
        _oneShotLocalDates.value = if (addr != null) AlarmSyncStorage.loadState(appContext, addr).oneShotLocalDates() else emptyMap()
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
        // Display refresh only. Persisted expiry belongs to the application coordinator.
        refreshSyncRiskState()
    }

    private suspend fun hasLocalAppAlarms(localDraft: List<Alarm>?, addr: String?): Boolean {
        if (localDraft.isNullOrEmpty()) return false
        if (localDraft.any { it.enabled || it.hour != 0 || it.minute != 0 || !it.name.isNullOrBlank() || it.hasHourlyChime }) {
            return true
        }
        val days = if (addr != null) AlarmSyncStorage.loadDaySelections(appContext, addr) else emptyMap()
        return days.values.any { it.isNotEmpty() }
    }

    private fun loadAlarms() = viewModelScope.launch(Dispatchers.IO) {
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
            if (addr != null) {
                alarmCoordinator.update(addr) { state ->
                    if (state.alarms.isNotEmpty()) state else state.copy(alarms = newAlarms.map {
                        AlarmSyncStorage.StoredAlarm(it.hour, it.minute, it.enabled, it.hasHourlyChime, it.name)
                    })
                }
                loadLocalStateForAddress(addr)
            }
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

    private suspend fun updateAlarm(
        index: Int,
        days: Set<DayOfWeek>? = null,
        toggleDay: DayOfWeek? = null,
        reenable: Boolean = false,
        transform: (Alarm) -> Alarm,
    ) {
        val addr = watchSelector.currentAddress() ?: return
        alarmCoordinator.update(addr) { stored ->
            val state = if (stored.alarms.isEmpty()) stored.copy(alarms = createDefaultLocalAlarms().map {
                AlarmSyncStorage.StoredAlarm(it.hour, it.minute, it.enabled, it.hasHourlyChime, it.name)
            }) else stored
            val previous = state.alarms.getOrNull(index) ?: return@update state
            val alarm = transform(state.watchAlarms()[index])
            val edited = previous.copy(hour = alarm.hour, minute = alarm.minute, enabled = alarm.enabled,
                hasHourlyChime = alarm.hasHourlyChime, name = alarm.name)
            val selectedDays = days ?: state.days[index] ?: AlarmSchedulePlanner.AllDays
            val effectiveDays = if (toggleDay == null) selectedDays else
                if (toggleDay in selectedDays) selectedDays - toggleDay else selectedDays + toggleDay
            editAlarmState(state, index, edited, effectiveDays, reenable, alarmTime.clock, alarmTime.zone())
        }
        loadLocalStateForAddress(addr)
    }

    fun toggleAlarm(index: Int, isEnabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        updateAlarm(index, reenable = isEnabled) { it.copy(enabled = isEnabled) }
        refreshSyncRiskState()
    }

    fun onTimeChanged(index: Int, hours: Int, minutes: Int) = viewModelScope.launch(Dispatchers.IO) {
        updateAlarm(index) { it.copy(hour = hours, minute = minutes, name = null) }
        refreshSyncRiskState()
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
        refreshSyncRiskState()
    }

    fun sendHourlySignalToWatch() = viewModelScope.launch {
        val addr = watchSelector.currentAddress() ?: return@launch
        if (!isSelectedWatchConnected(addr)) {
            AppSnackbar(appContext.getString(R.string.watch_not_connected))
            return@launch
        }
        val settings = HourlySignalSettings.load(appContext, addr)
        val alarmSnapshot = alarmCoordinator.snapshot(addr)
        val syncTime = LocalDateTime.now()
        val hourlySignalTarget = HourlySignalEvaluator.shouldChimeAt(syncTime, settings)
        val result = runCatching {
            applyHourlySignalToWatch(api, settings, syncTime, consumedSlots = alarmSnapshot.firedAts().keys,
                oneShotOccurrences = alarmSnapshot.oneShotOccurrences(), clock = alarmTime.clock, zone = alarmTime.zone())
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
            alarmCoordinator.reconcile(addr)
            AppSnackbar(appContext.getString(R.string.hourly_signal_sent_to_watch))
        } else {
            ProgressEvents.onNext("Error", result.exceptionOrNull()?.message ?: "")
        }
    }

    fun setViewMode(mode: AlarmViewMode) = viewModelScope.launch(Dispatchers.IO) {
        _viewMode.value = AlarmViewMode.WEEKLY
        val addr = watchSelector.currentAddress() ?: return@launch
        AlarmSyncStorage.saveViewMode(appContext, addr, AlarmViewMode.WEEKLY)
    }

    fun toggleDay(alarmIndex: Int, day: DayOfWeek) = viewModelScope.launch(Dispatchers.IO) {
        updateAlarm(alarmIndex, toggleDay = day) { it }
    }

    fun sendAlarmsToWatch() = viewModelScope.launch(Dispatchers.IO) {
        WatchSyncActivity.track {
        val addr = watchSelector.currentAddress() ?: return@launch
        if (!isSelectedWatchConnected(addr)) {
            AppSnackbar(appContext.getString(R.string.watch_not_connected))
            return@launch
        }

        // snapshot() persists expiry under the coordinator lock before constructing any BLE payload.
        val snapshot = alarmCoordinator.snapshot(addr)
        if (snapshot.alarms.isEmpty()) return@launch
        val desiredAlarms = snapshot.watchAlarms().mapIndexed { index, alarm ->
            if (alarm.name == null) {
                alarmNameStorage.put("", index)
                alarm.copy(name = "")
            } else alarm
        }
        runCatching { alarmNameStorage.save() }
            .onFailure {
                Timber.w(it, "Saving alarm names to watch failed")
                ProgressEvents.onNext("Error", it.message ?: "")
                return@launch
            }
        val alarmsToSend = AlarmSchedulePlanner.applyWeeklySchedule(
            alarms = desiredAlarms,
            alarmDays = snapshot.days,
            now = LocalDateTime.ofInstant(alarmTime.clock.instant(), alarmTime.zone()),
            viewMode = AlarmViewMode.WEEKLY,
            firedAts = snapshot.firedAts(),
            oneShotLocalDates = snapshot.oneShotLocalDates(),
            clock = alarmTime.clock,
            zone = alarmTime.zone(),
        )

        val signalSettings = HourlySignalSettings.load(appContext, addr)
        val syncTime = LocalDateTime.now()
        val hourlySignalTarget = HourlySignalEvaluator.shouldChimeAt(syncTime, signalSettings)

        val result = runCatching {
            api.getAlarms()
            val alarmsWithChime = AlarmSchedulePlanner.mirrorChime(alarmsToSend, hourlySignalTarget)
            api.setAlarms(ArrayList(alarmsWithChime))
            if (WatchInfo.chimeInSettings) {
                applyHourlySignalToWatch(api, signalSettings, syncTime)
            }
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
            alarmCoordinator.update(addr) { current ->
                // Do not acknowledge an edit made while the BLE write was in flight.
                alarmStateAfterSuccessfulSync(current, snapshot)
            }
            alarmCoordinator.reconcile(addr)
            refreshSyncRiskState()
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
            oneShotLocalDate = _oneShotLocalDates.value[index],
        )
    }

    fun dismissEditor() {
        _editorTarget.value = null
    }

    fun upsertAlarm(index: Int, hour: Int, minute: Int, name: String, days: Set<DayOfWeek>) = viewModelScope.launch(Dispatchers.IO) {
        val isFireOnce = days.isEmpty()
        if (isFireOnce && !exactAlarmPermissionRequested) {
            exactAlarmPermissionRequested = true
            viewModelScope.launch { _uiEvents.emit(UiEvent.RequestExactAlarmPermission) }
        }

        updateAlarm(index, days = days) {
            it.copy(hour = hour, minute = minute, name = name.ifBlank { null }, enabled = true)
        }
        refreshSyncRiskState()
        _editorTarget.value = null
    }

    fun sendAlarmsToPhone() {
        val allDays = arrayListOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )

        viewModelScope.launch(Dispatchers.IO) {
            val addr = watchSelector.currentAddress() ?: return@launch
            val state = alarmCoordinator.snapshot(addr)
            val requests = state.watchAlarms().withIndex()
                .filter { it.value.enabled && it.index !in state.firedAts() }
            if (requests.isEmpty()) return@launch
            requests.forEach { (index, alarm) ->
                    val selectedDays = state.days[index]
                    val days = when {
                        selectedDays == null -> allDays
                        selectedDays.isEmpty() -> null
                        else -> ArrayList(selectedDays.map { it.toCalendarDay() })
                    }

                    val effectiveTime = state.oneShotOccurrences()[index]?.resolveInstant(alarmTime.zone())?.let {
                        alarmTimeAtOccurrence(it.toEpochMilli(), alarmTime.zone())
                    } ?: LocalTime.of(alarm.hour, alarm.minute)
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_MESSAGE, alarm.name)
                        putExtra(AlarmClock.EXTRA_HOUR, effectiveTime.hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, effectiveTime.minute)
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
            AppSnackbar(appContext.getString(R.string.phone_alarm_handoff_limits))
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

    private fun refreshSyncRiskState() = viewModelScope.launch(Dispatchers.IO) {
        val addr = watchSelector.currentAddress()
        if (addr == null) {
            _syncWarnings.value = emptyMap()
            return@launch
        }

        val settings = WatchSettingsCache.load(appContext, addr)
        val autoSyncEnabled = LocalDataStorage.getOrInitializeAutoSyncOnReconnect(
            context = appContext,
            address = addr,
            seed = settings?.timeAdjustment,
        )
        val adjustmentTimeMinutes = settings?.adjustmentTimeMinutes ?: 0
        val history = store.load(addr)
        val state = AlarmSyncStorage.loadState(appContext, addr)
        val warnings = mutableMapOf<Int, String>()

        _alarms.value.forEachIndexed { index, alarm ->
            val risk = AlarmSyncRiskEvaluator.evaluate(
                key = AlarmKey(addr, index),
                alarm = state.alarms.getOrNull(index) ?: return@forEachIndexed,
                alarmDays = _alarmDays.value[index] ?: AlarmSchedulePlanner.AllDays,
                payloads = state.watchPayloads,
                clock = alarmTime.clock,
                zone = alarmTime.zone(),
                autoSyncEnabled = autoSyncEnabled,
                adjustmentTimeMinutes = adjustmentTimeMinutes,
            )

            if (risk != null) {
                warnings[index] = appContext.getString(
                    if (risk.nextAutoSyncAt == null) R.string.alarm_sync_risk_auto_off
                    else R.string.alarm_sync_risk_before_auto_sync,
                    risk.nextAlarmAt.format(warningTimeFormatter)
                )
            }
        }

        _syncWarnings.value = warnings
    }
}
