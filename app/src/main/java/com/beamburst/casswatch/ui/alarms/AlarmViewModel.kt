// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.pairing.WatchWriteGuard
import com.beamburst.casswatch.data.local.WatchConnectionSession
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
    private val connectionSession: WatchConnectionSession,
    private val writeGuard: WatchWriteGuard,
    private val payloadWriter: AlarmPayloadWriter,
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
    private val _fallbackStatuses = MutableStateFlow<Map<Int, String>>(emptyMap())
    val fallbackStatuses: StateFlow<Map<Int, String>> = _fallbackStatuses.asStateFlow()

    fun openPhoneClock() = openFallbackClock(appContext)

    val phoneAlarmBackupEnabled = LocalDataStorage.phoneAlarmBackupEnabledFlow(appContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setPhoneAlarmBackupEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        alarmCoordinator.serialized { LocalDataStorage.setPhoneAlarmBackupEnabled(appContext, enabled) }
        if (enabled && !canScheduleExactAlarms(appContext)) {
            _uiEvents.emit(UiEvent.RequestExactAlarmPermission)
        }
        alarmCoordinator.reconcileAll()
    }

    fun reconcileAlarmBackup() = viewModelScope.launch(Dispatchers.IO) {
        alarmCoordinator.reconcileAll()
    }

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
                        _isConnected.value = isSelectedWatchConnected(watchSelector.currentAddress())
                        loadAlarms()
                    }
                },
                EventAction("WatchInitializationCompleted") {
                    viewModelScope.launch {
                        _isConnected.value = isSelectedWatchConnected(watchSelector.currentAddress())
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

            val ready = connectionSession.matching(addr) ?: return@runCatching
            writeGuard.readApi(ready, api) { watch, confirm ->
                val names = watch.getScratchpadData(alarmNameStorage.getStorageOffset(), alarmNameStorage.getStorageSize())
                confirm()
                alarmNameStorage.setBuffer(names.copyOf())
                val alarmsFromWatch = watch.getAlarms()
                    .take(WatchInfo.alarmCount)
                    .mapIndexed { index, alarm ->
                        val name = alarmNameStorage.get(index)
                        alarm.copy(name = name)
                    }

                val newAlarms = if (WatchInfo.chimeInSettings) {
                    val settings = watch.getSettings()
                    alarmsFromWatch.mapIndexed { index, alarm ->
                        if (index == 0) alarm.copy(hasHourlyChime = settings.hourlyChime)
                        else alarm
                    }
                } else {
                    alarmsFromWatch
                }
                confirm()
                seedHourlySignalSettingsFromWatch(ready.address, newAlarms.getOrNull(0)?.hasHourlyChime ?: false)
                _alarms.value = newAlarms
                alarmCoordinator.update(ready.address) { state ->
                    if (state.alarms.isNotEmpty()) state else state.copy(alarms = newAlarms.map {
                            AlarmSyncStorage.StoredAlarm(it.hour, it.minute, it.enabled, it.hasHourlyChime, it.name)
                    })
                }
                confirm()
                loadLocalStateForAddress(ready.address)
                ProgressEvents.onNext("Alarms Loaded")
            }
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

    fun sendHourlySignalToWatch() = viewModelScope.launch(Dispatchers.IO) {
        val ready = writeGuard.capture() ?: return@launch
        writeGuard.runApi(ready, api) { watch, confirm ->
            WatchSyncActivity.track {
                val settings = HourlySignalSettings.load(appContext, ready.address)
                val syncTime = LocalDateTime.ofInstant(alarmTime.clock.instant(), alarmTime.zone())
                val result = runCatching {
                    payloadWriter.hourlySignal(watch, ready, settings, syncTime, SyncTrigger.MANUAL_SEND_ALARMS, confirm)
                }
                confirm()
                syncLogger.record(address = ready.address, trigger = SyncTrigger.MANUAL_SEND_ALARMS,
                    kinds = setOf(SyncKind.HOURLY_SIGNAL), success = result.isSuccess,
                    errorMessage = result.exceptionOrNull()?.message,
                    details = SyncDetails.Empty.copy(hourlySignalTarget = HourlySignalEvaluator.shouldChimeAt(syncTime, settings)))
                confirm()
                if (result.isSuccess) AppSnackbar(appContext.getString(R.string.hourly_signal_sent_to_watch))
                else ProgressEvents.onNext("Error", result.exceptionOrNull()?.message ?: "")
            }
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
        val ready = writeGuard.capture() ?: return@launch
        writeGuard.runApi(ready, api) { watch, confirm ->
            WatchSyncActivity.track {
                // Persist expiry before constructing any BLE payload.
                val snapshot = alarmCoordinator.snapshot(ready.address)
                if (snapshot.alarms.isEmpty()) return@track
                val desiredAlarms = snapshot.watchAlarms().mapIndexed { index, alarm ->
                    alarmNameStorage.put(alarm.name.orEmpty(), index)
                    alarm.copy(name = alarm.name.orEmpty())
                }
                val syncTime = LocalDateTime.ofInstant(alarmTime.clock.instant(), alarmTime.zone())
                val signalSettings = HourlySignalSettings.load(appContext, ready.address)
                val target = HourlySignalEvaluator.shouldChimeAt(syncTime, signalSettings)
                val scheduled = AlarmSchedulePlanner.applyWeeklySchedule(desiredAlarms, snapshot.days, syncTime,
                    AlarmViewMode.WEEKLY, snapshot.firedAts(), snapshot.oneShotLocalDates(), alarmTime.zone(), alarmTime.clock)
                val payload = if (WatchInfo.chimeInSettings) scheduled else AlarmSchedulePlanner.mirrorChime(scheduled, target)
                val result = runCatching {
                    watch.setScratchpadData(alarmNameStorage.getBuffer().copyOf(), alarmNameStorage.getStorageOffset())
                    payloadWriter.write(ready, payload, snapshot, SyncTrigger.MANUAL_SEND_ALARMS, target, confirm, { watch.getAlarms() }) { actual ->
                        watch.setAlarms(ArrayList(actual))
                    }
                    if (WatchInfo.chimeInSettings) {
                        payloadWriter.hourlySignal(watch, ready, signalSettings, syncTime, SyncTrigger.MANUAL_SEND_ALARMS, confirm)
                    }
                    confirm()
                    alarmCoordinator.update(ready.address) { current ->
                        if (!connectionSession.isCurrent(ready)) throw com.beamburst.casswatch.pairing.WatchConnectionChangedException()
                        alarmStateAfterSuccessfulSync(current, snapshot)
                    }
                    confirm()
                    refreshSyncRiskState()
                    AppSnackbar(appContext.getString(R.string.alarms_set_to_watch))
                }
                confirm()
                result.onFailure { ProgressEvents.onNext("Error", appContext.alarmOperationError(it)) }
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

    private fun isSelectedWatchConnected(addr: String?): Boolean =
        api.isConnected() && connectionSession.matching(addr) != null

    private fun refreshSyncRiskState() = viewModelScope.launch(Dispatchers.IO) {
        val addr = watchSelector.currentAddress()
        if (addr == null) {
            _syncWarnings.value = emptyMap()
            _fallbackStatuses.value = emptyMap()
            return@launch
        }

        val settings = WatchSettingsCache.load(appContext, addr)
        val autoSyncEnabled = LocalDataStorage.getOrInitializeAutoSyncOnReconnect(
            context = appContext,
            address = addr,
            seed = settings?.timeAdjustment,
        )
        // The schedule the watch actually follows: unsent edits are not on the watch yet.
        val onWatch = if (WatchSettingsCache.isDirty(appContext, addr)) {
            WatchSettingsCache.loadBaseline(appContext, addr) ?: settings
        } else {
            settings
        }
        val adjustmentTimeMinutes = onWatch?.adjustmentTimeMinutes ?: 0
        val adjustmentTimeHour = onWatch?.adjustmentTimeHour ?: 0
        val state = AlarmSyncStorage.loadState(appContext, addr)
        _fallbackStatuses.value = state.alarms.mapIndexedNotNull { slot, alarm ->
            when (activeFallbackStatus(alarm, alarmTime.clock.millis())) {
                "failed" -> slot to appContext.getString(R.string.alarm_fallback_failed)
                "pending" -> slot to appContext.getString(R.string.alarm_fallback_unconfirmed)
                "requested" -> slot to appContext.getString(R.string.alarm_fallback_requested)
                else -> null
            }
        }.toMap()
        val warnings = mutableMapOf<Int, String>()

        _alarms.value.forEachIndexed { index, alarm ->
            val risk = AlarmSyncRiskEvaluator.evaluate(
                key = AlarmKey(addr, index),
                alarm = state.alarms.getOrNull(index) ?: return@forEachIndexed,
                alarmDays = _alarmDays.value[index] ?: AlarmSchedulePlanner.AllDays,
                payloads = state.confirmedPayloads(),
                clock = alarmTime.clock,
                zone = alarmTime.zone(),
                autoSyncEnabled = autoSyncEnabled,
                adjustmentTimeMinutes = adjustmentTimeMinutes,
                adjustmentTimeHour = adjustmentTimeHour,
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
