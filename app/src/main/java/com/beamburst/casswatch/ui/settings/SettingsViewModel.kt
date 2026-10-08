// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.ui.settings

import CachedIO
import com.beamburst.casswatch.ui.alarms.alarmOperationError
import com.beamburst.casswatch.pairing.confirmWatchWrite
import com.beamburst.casswatch.pairing.guardWrites
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.pairing.WatchWriteGuard
import com.beamburst.casswatch.utils.WatchSyncActivity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.beamburst.casswatch.R
import com.beamburst.casswatch.BroadcastSpec
import com.beamburst.casswatch.PressBroadcastEmitter
import com.beamburst.casswatch.data.local.BroadcastCardState
import com.beamburst.casswatch.data.local.BroadcastExtra
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.FindPhoneCardState
import com.beamburst.casswatch.data.local.FindPhoneMelody
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.data.local.PressCardRepository
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import com.beamburst.casswatch.data.local.WatchSettingsCache
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.ui.alarms.HourlySignalEvaluator
import com.beamburst.casswatch.ui.alarms.applyReconciledHourlySignal
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.NtpTimeSettings
import com.beamburst.casswatch.utils.PhoneFinder
import com.beamburst.casswatch.utils.SyncHistoryLogger
import com.beamburst.casswatch.utils.TimeWriteResult
import com.beamburst.casswatch.utils.WatchTimeProvider
import com.beamburst.casswatch.utils.WatchTimeWriter
import com.beamburst.casswatch.utils.syncHistoryActions
import org.avmedia.gshockapi.Settings
import org.avmedia.gshockapi.WatchInfo
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDateTime

abstract class Setting(val name: String) {
    open suspend fun save() {} // Default empty implementation
}

data class SettingsState(
        val settings: List<Setting> = emptyList(),
        val settingsMap: Map<Class<out Setting>, Setting> = emptyMap(),
        val address: String? = null,
        val baseline: Settings? = null,
        val draft: Settings? = null,
) {
    val hasBaseline: Boolean get() = baseline != null && draft != null

    internal fun withBaseline(address: String?, loaded: Settings): SettingsState = copy(
        address = address,
        baseline = loaded.copy(),
        draft = mergeSettingsRefresh(loaded, baseline, draft),
    )

    fun settingsForSend(selectedAddress: String?): Settings? =
        draft?.takeIf { hasBaseline && address != null && address == selectedAddress }?.copy()
}

sealed class SettingsAction {
    data class UpdateSetting<T : Setting>(val setting: T) : SettingsAction()
    data object SendToWatch : SettingsAction()
}

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
}

data class PressCardsUiState(
    val shortBroadcast: BroadcastCardState = BroadcastCardState(),
    val longBroadcast:  BroadcastCardState = BroadcastCardState(),
    val findPhone:      FindPhoneCardState = FindPhoneCardState(),
)

data class NtpTimeUiState(
    val enabled: Boolean = false,
    val server: String = NtpTimeSettings.DEFAULT_SERVER,
)

data class AutoSyncOnReconnectUiState(
    val enabled: Boolean = false,
)

sealed class PressCardAction {
    data class ToggleShortBroadcast(val on: Boolean)  : PressCardAction()
    data class ToggleLongBroadcast(val on: Boolean)   : PressCardAction()
    data class ToggleFindPhone(val on: Boolean)       : PressCardAction()
    data class SaveFindPhoneConfig(val volumePercent: Int, val melody: FindPhoneMelody) : PressCardAction()
    data class SaveShortExtras(val extras: List<BroadcastExtra>) : PressCardAction()
    data class SaveLongExtras(val extras: List<BroadcastExtra>)  : PressCardAction()
}

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
        private val api: GShockRepository,
        private val connectionSession: WatchConnectionSession,
        private val writeGuard: WatchWriteGuard,
        @param:ApplicationContext private val appContext: Context,
        private val pressCardRepository: PressCardRepository,
        private val syncLogger: SyncHistoryLogger,
        private val watchSelector: CurrentWatchSelector,
        private val connectionTracker: WatchConnectionTracker,
        private val watchTimeProvider: WatchTimeProvider,
        private val watchTimeWriter: WatchTimeWriter,
) : ViewModel() {

    fun onSettingUpdated(setting: Setting) {
        updateSetting(setting)
    }

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private val _pressCards = MutableStateFlow(PressCardsUiState())
    val pressCards: StateFlow<PressCardsUiState> = _pressCards.asStateFlow()

    private val _ntpTime = MutableStateFlow(NtpTimeUiState())
    val ntpTime: StateFlow<NtpTimeUiState> = _ntpTime.asStateFlow()
    private val _autoSyncOnReconnect = MutableStateFlow(AutoSyncOnReconnectUiState())
    val autoSyncOnReconnect: StateFlow<AutoSyncOnReconnectUiState> = _autoSyncOnReconnect.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        initializeSettings()
        loadNtpTimeSettings()
        observeSelectedWatchSettings()
        observeConnectionState()
        loadPressCards()
    }

    private fun initializeSettings() {
        updateSettingsAndMap(defaultSettings())
    }

    private fun allSettings(): ArrayList<Setting> =
            arrayListOf(
                    Locale(),
                    OperationSound(),
                    Light(),
                    PowerSavingMode(),
                    Font(),
                    TimeAdjustment(appContext),
            )

    private fun defaultSettings(): ArrayList<Setting> = filter(allSettings())

    private fun observeSelectedWatchSettings() {
        viewModelScope.launch {
            settingsReloads(watchSelector.selected, connectionSession.initialized).collectLatest { (addr, ready) ->
                runCatching { loadSettingsForAddress(addr, ready) }
                    .onFailure { Timber.w(it, "Failed to load settings for %s", addr) }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            combine(watchSelector.selected, connectionSession.initialized) { addr, ready ->
                ready != null && ready.address == addr && connectionSession.isInitialized(ready)
            }.collect { connected -> _isConnected.value = connected }
        }
    }

    private suspend fun loadSettingsForAddress(addr: String?, ready: WatchConnectionSession.Ready?) {
        if (state.value.address != addr) {
            _state.value = SettingsState(address = addr)
            updateSettingsAndMap(defaultSettings())
        }
        val (cached, dirty) = withContext(Dispatchers.IO) {
            loadAutoSyncOnReconnectForAddress(addr)
            (addr?.let { WatchSettingsCache.load(appContext, it) }) to
                (addr != null && WatchSettingsCache.isDirty(appContext, addr))
        }
        if (watchSelector.currentAddress() != addr) return
        if (cached != null) acceptSettings(addr, cached)
        if (ready == null || dirty) return

        writeGuard.readApi(ready, api) { boundApi, confirm ->
            val settings = boundApi.getSettings()
            confirm()
            if (watchSelector.currentAddress() != addr) return@readApi
            acceptSettings(addr, settings)
            val draft = buildApiSettings() ?: return@readApi
            withContext(Dispatchers.IO) {
                if (draft == settings) WatchSettingsCache.saveClean(appContext, ready.address, settings)
                else WatchSettingsCache.save(appContext, ready.address, draft)
            }
        }
    }

    private fun acceptSettings(addr: String?, settings: Settings) {
        val refreshed = state.value.withBaseline(addr, settings)
        val localAdjustment = state.value.settingsMap[TimeAdjustment::class.java] as? TimeAdjustment
        updateSettingsAndMap(defaultSettings())
        val loaded = fromJson(Gson().toJson(refreshed.draft))
        // These two controls are phone preferences, not fields read from the watch.
        loaded.filterIsInstance<TimeAdjustment>().firstOrNull()?.let {
            if (localAdjustment != null) {
                it.fineAdjustment = localAdjustment.fineAdjustment
                it.timeAdjustmentNotifications = localAdjustment.timeAdjustmentNotifications
            }
        }
        updateSettingsAndMap(loaded)
        _state.update { it.copy(address = addr, baseline = refreshed.baseline, draft = refreshed.draft) }
    }

    private suspend fun loadAutoSyncOnReconnectForAddress(addr: String?) {
        if (addr == null) {
            _autoSyncOnReconnect.value = AutoSyncOnReconnectUiState(enabled = false)
            return
        }
        val seed = WatchSettingsCache.load(appContext, addr)?.timeAdjustment
        val enabled = LocalDataStorage.getOrInitializeAutoSyncOnReconnect(
            context = appContext,
            address = addr,
            seed = seed,
        )
        _autoSyncOnReconnect.value = AutoSyncOnReconnectUiState(enabled = enabled)
    }

    fun setAutoSyncOnReconnect(enabled: Boolean) {
        _autoSyncOnReconnect.value = AutoSyncOnReconnectUiState(enabled = enabled)
        val addr = watchSelector.currentAddress() ?: return
        viewModelScope.launch {
            LocalDataStorage.setAutoSyncOnReconnect(appContext, addr, enabled)
        }
    }

    private fun updateSettingsAndMap(newSettings: ArrayList<Setting>) {
        val newMap = newSettings.associateBy { it::class.java }
        _state.update { it.copy(settings = newSettings, settingsMap = newMap) }
    }

    private fun loadNtpTimeSettings() {
        _ntpTime.value = NtpTimeUiState(
            enabled = LocalDataStorage.getNtpTimeEnabled(appContext),
            server = LocalDataStorage.getNtpTimeServer(appContext),
        )
    }

    fun setNtpTimeEnabled(enabled: Boolean) {
        _ntpTime.update { it.copy(enabled = enabled) }
        viewModelScope.launch {
            LocalDataStorage.setNtpTimeEnabled(appContext, enabled)
            if (enabled) watchTimeProvider.refreshNtpInBackground()
        }
    }

    fun setNtpTimeServer(rawServer: String): Boolean {
        val normalized = NtpTimeSettings.normalizeServer(rawServer) ?: return false
        _ntpTime.update { it.copy(server = normalized) }
        viewModelScope.launch {
            LocalDataStorage.setNtpTimeServer(appContext, normalized)
            watchTimeProvider.refreshNtpInBackground()
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Setting> getSetting(type: Class<T>): T {
        (state.value.settingsMap[type] as? T)?.let { return it }
        Timber.w("Setting %s missing from state; using a default instance", type.simpleName)
        return allSettings().first { it::class.java == type } as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Setting> settingOrNull(type: Class<T>): T? {
        val setting = state.value.settingsMap[type] as? T
        if (setting == null) {
            Timber.w("Setting %s not present in state; skipping update", type.simpleName)
        }
        return setting
    }

    /**
     * Updates a single setting in both the settings list and settings map, then persists the
     * change.
     *
     * @param updatedSetting The new setting instance that will replace the existing one
     *
     * The function:
     * 1. Makes a mutable copy of the current settings list
     * 2. Finds the matching setting by its class type
     * 3. Updates both the list and map with the new setting
     * 4. Updates the state with the new collections
     * 5. Calls save() on the setting to persist changes
     *
     * Note: If no matching setting is found (index == -1), no update occurs
     */
    private fun updateSetting(updatedSetting: Setting) {
        val current = state.value
        val address = watchSelector.currentAddress() ?: return
        val draft = current.settingsForSend(address) ?: return
        val previousSetting = current.settingsMap[updatedSetting::class.java] ?: return
        val settings = applySettingsEdit(draft, previousSetting, updatedSetting)
        val currentList = state.value.settings.toMutableList()
        val index = currentList.indexOfFirst { it::class == updatedSetting::class }
        if (index != -1) {
            currentList[index] = updatedSetting
            val newMap = state.value.settingsMap.toMutableMap()
            newMap[updatedSetting::class.java] = updatedSetting
            _state.update { it.copy(settings = currentList, settingsMap = newMap, draft = settings) }
            viewModelScope.launch {
                updatedSetting.save()
                WatchSettingsCache.save(appContext, address, settings)
            }
        }
    }

    data class Locale(
            var timeFormat: TimeFormat = TimeFormat.TWELVE_HOURS,
            var dateFormat: DateFormat = DateFormat.MONTH_DAY,
            var dayOfWeekLanguage: DayOfWeekLanguage = DayOfWeekLanguage.ENGLISH,
    ) : Setting("Locale") {
        enum class TimeFormat(val value: String) {
            TWELVE_HOURS("12h"),
            TWENTY_FOUR_HOURS("24h"),
        }

        enum class DateFormat(val value: String) {
            MONTH_DAY("MM:DD"),
            DAY_MONTH("DD:MM"),
        }

        enum class DayOfWeekLanguage(val nativeName: String, val englishName: String) {
            ENGLISH("English", "English"),
            SPANISH("Español", "Spanish"),
            FRENCH("Français", "French"),
            GERMAN("Deutsch", "German"),
            ITALIAN("Italiano", "Italian"),
            RUSSIAN("Русский", "Russian")
        }
    }

    data class OperationSound(var sound: Boolean = true, var vibrate: Boolean = false) :
            Setting("Button Sound")

    data class Light(
            var autoLight: Boolean = false,
            var duration: LightDuration = LightDuration.ONE_POINT_FIVE_SECONDS,
    ) : Setting("Light") {
        enum class LightDuration(val value: String) {
            ONE_POINT_FIVE_SECONDS("1.5s"),
            THREE_SECONDS("3s")
        }
    }

    data class PowerSavingMode(var powerSavingMode: Boolean = false) : Setting("Power Saving Mode")

    data class TimeAdjustment(
            val appContext: Context,
            var timeAdjustment: Boolean = true,
            var adjustmentTimeMinutes: Int = 0,
            var timeAdjustmentNotifications: Boolean =
                    LocalDataStorage.getTimeAdjustmentNotification(appContext),
            var fineAdjustment: Int = LocalDataStorage.getFineTimeAdjustment(appContext),
    ) : Setting("Time Adjustment") {
        override suspend fun save() {
            LocalDataStorage.setTimeAdjustmentNotification(appContext, timeAdjustmentNotifications)
            LocalDataStorage.setFineTimeAdjustment(appContext, fineAdjustment)
        }
    }

    data class DnD(
            var dnd: Boolean = true,
    ) : Setting("DnD")

    data class Font(var font: FontType = FontType.STANDARD) : Setting("Font") {
        enum class FontType(val value: String) {
            STANDARD("Standard"),
            CLASSIC("Classic")
        }
    }

    private fun filter(settings: ArrayList<Setting>): ArrayList<Setting> {
        return settings.filter { setting ->
            when (setting) {
                is PowerSavingMode -> WatchInfo.hasPowerSavingMode
                is Font -> WatchInfo.hasMultipleFonts
                else -> true
            }
        } as
                ArrayList<Setting>
    }

    @Synchronized
    fun fromJson(jsonStr: String): ArrayList<Setting> {
        val updatedObjects = mutableSetOf<Setting>()
        val jsonObj = JSONObject(jsonStr)
        val keys = jsonObj.keys()

        while (keys.hasNext()) {
            val key: String = keys.next()
            val value = jsonObj.get(key)

            when (key) {
                "powerSavingMode" -> handlePowerSavingMode(value, updatedObjects)
                "timeAdjustment" -> handleTimeAdjustment(value, updatedObjects)
                "adjustmentTimeMinutes" -> handleAdjustmentTimeMinutes(value, updatedObjects)
                "buttonTone" -> handleButtonTone(value, updatedObjects)
                "keyVibration" -> handleButtonVibration(value, updatedObjects)
                "autoLight" -> handleAutoLight(value, updatedObjects)
                "lightDuration" -> handleLightDuration(value, updatedObjects)
                "timeFormat" -> handleTimeFormat(value, updatedObjects)
                "dateFormat" -> handleDateFormat(value, updatedObjects)
                "language" -> handleLanguage(value, updatedObjects)
                "font" -> handleFont(value, updatedObjects)
            }
        }

        return ArrayList(updatedObjects)
    }

    private fun handlePowerSavingMode(value: Any, updatedObjects: MutableSet<Setting>) {
        if (WatchInfo.hasPowerSavingMode) {
            val setting = settingOrNull(PowerSavingMode::class.java) ?: return
            setting.powerSavingMode = value == true
            updatedObjects.add(setting)
        }
    }

    private fun handleTimeAdjustment(value: Any, updatedObjects: MutableSet<Setting>) {
        val setting = settingOrNull(TimeAdjustment::class.java) ?: return
        setting.timeAdjustment = value == true
        updatedObjects.add(setting)
    }

    private fun handleAdjustmentTimeMinutes(value: Any, updatedObjects: MutableSet<Setting>) {
        if (!WatchInfo.alwaysConnected) {
            val setting = settingOrNull(TimeAdjustment::class.java) ?: return
            setting.adjustmentTimeMinutes = (value as? Number)?.toInt() ?: return
            updatedObjects.add(setting)
        }
    }

    private fun handleButtonTone(value: Any, updatedObjects: MutableSet<Setting>) {
        val setting = settingOrNull(OperationSound::class.java) ?: return
        setting.sound = value == true
        updatedObjects.add(setting)
    }

    private fun handleButtonVibration(value: Any, updatedObjects: MutableSet<Setting>) {
        val setting = settingOrNull(OperationSound::class.java) ?: return
        setting.vibrate = value == true
        updatedObjects.add(setting)
    }

    private fun handleAutoLight(value: Any, updatedObjects: MutableSet<Setting>) {
        val setting = settingOrNull(Light::class.java) ?: return
        setting.autoLight = value == true
        updatedObjects.add(setting)
    }

    private fun handleLightDuration(value: Any, updatedObjects: MutableSet<Setting>) {
        val setting = settingOrNull(Light::class.java) ?: return
        setting.duration =
                if (value == Light.LightDuration.ONE_POINT_FIVE_SECONDS.value) {
                    Light.LightDuration.ONE_POINT_FIVE_SECONDS
                } else {
                    Light.LightDuration.THREE_SECONDS
                }
        updatedObjects.add(setting)
    }

    private fun handleTimeFormat(value: Any, updatedObjects: MutableSet<Setting>) {
        val setting = settingOrNull(Locale::class.java) ?: return
        setting.timeFormat =
                if (value == Locale.TimeFormat.TWELVE_HOURS.value) {
                    Locale.TimeFormat.TWELVE_HOURS
                } else {
                    Locale.TimeFormat.TWENTY_FOUR_HOURS
                }
        updatedObjects.add(setting)
    }

    private fun handleDateFormat(value: Any, updatedObjects: MutableSet<Setting>) {
        val setting = settingOrNull(Locale::class.java) ?: return
        setting.dateFormat =
                if (value == Locale.DateFormat.MONTH_DAY.value) {
                    Locale.DateFormat.MONTH_DAY
                } else {
                    Locale.DateFormat.DAY_MONTH
                }
        updatedObjects.add(setting)
    }

    private fun handleLanguage(value: Any, updatedObjects: MutableSet<Setting>) {
        val setting = settingOrNull(Locale::class.java) ?: return
        setting.dayOfWeekLanguage =
                when (value) {
                    Locale.DayOfWeekLanguage.ENGLISH.englishName -> Locale.DayOfWeekLanguage.ENGLISH
                    Locale.DayOfWeekLanguage.SPANISH.englishName -> Locale.DayOfWeekLanguage.SPANISH
                    Locale.DayOfWeekLanguage.FRENCH.englishName -> Locale.DayOfWeekLanguage.FRENCH
                    Locale.DayOfWeekLanguage.GERMAN.englishName -> Locale.DayOfWeekLanguage.GERMAN
                    Locale.DayOfWeekLanguage.ITALIAN.englishName -> Locale.DayOfWeekLanguage.ITALIAN
                    Locale.DayOfWeekLanguage.RUSSIAN.englishName -> Locale.DayOfWeekLanguage.RUSSIAN
                    else -> setting.dayOfWeekLanguage // No change if the language is unknown
                }
        updatedObjects.add(setting)
    }

    private fun handleFont(value: Any, updatedObjects: MutableSet<Setting>) {
        if (WatchInfo.hasMultipleFonts) {
            val setting = settingOrNull(Font::class.java) ?: return
            setting.font =
                    if (value == Font.FontType.CLASSIC.value) {
                        Font.FontType.CLASSIC
                    } else {
                        Font.FontType.STANDARD
                    }
            updatedObjects.add(setting)
        }
    }

    private fun loadPressCards() {
        viewModelScope.launch(Dispatchers.Default) {
            val short = pressCardRepository.shortPress()
            val long  = pressCardRepository.longPress()
            _pressCards.value = PressCardsUiState(
                shortBroadcast = short.broadcast,
                longBroadcast  = long.broadcast,
                findPhone      = long.findPhone,
            )
        }
    }

    fun onPressCardAction(action: PressCardAction) {
        when (action) {
            is PressCardAction.ToggleShortBroadcast -> {
                val updated = _pressCards.value.shortBroadcast.copy(enabled = action.on)
                _pressCards.value = _pressCards.value.copy(shortBroadcast = updated)
                pressCardRepository.saveShortBroadcast(updated)
            }
            is PressCardAction.ToggleLongBroadcast -> {
                val updated = _pressCards.value.longBroadcast.copy(enabled = action.on)
                _pressCards.value = _pressCards.value.copy(longBroadcast = updated)
                pressCardRepository.saveLongBroadcast(updated)
            }
            is PressCardAction.ToggleFindPhone -> {
                val updated = _pressCards.value.findPhone.copy(enabled = action.on)
                _pressCards.value = _pressCards.value.copy(findPhone = updated)
                pressCardRepository.saveFindPhoneConfig(updated)
            }
            is PressCardAction.SaveFindPhoneConfig -> {
                val updated = _pressCards.value.findPhone.copy(
                    volumePercent = action.volumePercent.coerceIn(0, 100),
                    melody = action.melody,
                )
                _pressCards.value = _pressCards.value.copy(findPhone = updated)
                pressCardRepository.saveFindPhoneConfig(updated)
            }
            is PressCardAction.SaveShortExtras -> {
                val updated = _pressCards.value.shortBroadcast.copy(extras = action.extras)
                _pressCards.value = _pressCards.value.copy(shortBroadcast = updated)
                pressCardRepository.saveShortBroadcast(updated)
            }
            is PressCardAction.SaveLongExtras -> {
                val updated = _pressCards.value.longBroadcast.copy(extras = action.extras)
                _pressCards.value = _pressCards.value.copy(longBroadcast = updated)
                pressCardRepository.saveLongBroadcast(updated)
            }
        }
    }

    fun sendTestBroadcast(spec: BroadcastSpec, extras: List<BroadcastExtra>) {
        PressBroadcastEmitter.send(appContext, spec, extras)
    }

    fun testFindPhoneConfig(volumePercent: Int, melody: FindPhoneMelody) {
        PhoneFinder.ring(appContext, volumePercent, melody)
    }

    fun stopFindPhoneTest() {
        PhoneFinder.stop()
    }

    fun setTimeNow() {
        val ready = writeGuard.capture() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val watchTime = watchTimeProvider.nowForWatch(ready.address)
            writeGuard.run(ready) { checkSession ->
                WatchSyncActivity.track {
                    watchTime.fallbackReason?.let { AppSnackbar(it) }
                    val addr = ready.address
                    val signalSettings = HourlySignalSettings.load(appContext, addr)
                    val now = LocalDateTime.now()
                    val hourlyTarget = HourlySignalEvaluator.shouldChimeAt(now, signalSettings)
                    var writeResult: TimeWriteResult? = null
                    val result = runCatching {
                        writeResult = watchTimeWriter.write(api, watchTime, ready, checkSession)
                        checkSession()
                        applyReconciledHourlySignal(appContext, api.guardWrites(checkSession), ready,
                            signalSettings, now, SyncTrigger.MANUAL_SETTINGS_SET_TIME) { confirmWatchWrite(checkSession) }
                    }
                    confirmWatchWrite(checkSession)
                    syncLogger.record(
                        address = ready.address,
                        trigger = SyncTrigger.MANUAL_SETTINGS_SET_TIME,
                        kinds = setOf(SyncKind.TIME, SyncKind.HOURLY_SIGNAL),
                        success = result.isSuccess,
                        errorMessage = result.exceptionOrNull()?.message,
                        details = SyncDetails(
                            alarmHashes = null,
                            eventCount = null,
                            hourlySignalTarget = hourlyTarget,
                            timeMs = writeResult?.timeMs,
                            actions = watchTime.syncHistoryActions("Settings screen set-time button", writeResult),
                        ),
                    )
                    if (result.isSuccess) {
                        AppSnackbar(appContext.getString(R.string.time_sent_to_watch))
                    } else {
                        AppSnackbar(appContext.alarmOperationError(result.exceptionOrNull()))
                    }
                }
            }
        }
    }

    fun sendToWatch() {
        val draft = buildApiSettings() ?: return
        val ready = writeGuard.capture() ?: return

        viewModelScope.launch {
            writeGuard.run(ready) { checkSession ->
                WatchSyncActivity.track {
                    val result = runCatching {
                        checkSession()
                        val settings = if (WatchInfo.chimeInSettings) {
                            // The hourly-signal writer may have changed this target since load.
                            CachedIO.remove("GET_SETTINGS")
                            val current = api.guardWrites(checkSession).getBasicSettings()
                            draft.copy(hourlyChime = current.hourlyChime)
                        } else draft
                        api.setSettings(settings)
                        confirmWatchWrite(checkSession)
                        // Edits made while the send was in flight are judged against what was
                        // sent, not against the baseline from before the send.
                        _state.update { it.copy(baseline = draft.copy()) }
                        acceptSettings(ready.address, settings)
                        val pending = buildApiSettings() ?: return@runCatching
                        if (pending == settings) WatchSettingsCache.saveClean(appContext, ready.address, settings)
                        else WatchSettingsCache.save(appContext, ready.address, pending)
                        AppSnackbar(appContext.getString(R.string.settings_sent_to_watch))
                    }
                    confirmWatchWrite(checkSession)
                    syncLogger.record(
                        address = ready.address,
                        trigger = SyncTrigger.MANUAL_SEND_SETTINGS,
                        kinds = setOf(SyncKind.SETTINGS),
                        success = result.isSuccess,
                        errorMessage = result.exceptionOrNull()?.message,
                        details = SyncDetails.Empty.copy(actions = listOf("Watch settings sent")),
                    )
                    result.onFailure { e -> AppSnackbar(appContext.alarmOperationError(e)) }
                }
            }
        }
    }

    private fun buildApiSettings(): Settings? = state.value.settingsForSend(watchSelector.currentAddress())

}

/** Refresh only replaces fields without a pending user edit. Noneditable fields come from the watch. */
internal fun mergeSettingsRefresh(loaded: Settings, baseline: Settings?, draft: Settings?): Settings {
    if (baseline == null || draft == null) return loaded.copy()
    return loaded.copy(
        language = if (draft.language != baseline.language) draft.language else loaded.language,
        timeFormat = if (draft.timeFormat != baseline.timeFormat) draft.timeFormat else loaded.timeFormat,
        dateFormat = if (draft.dateFormat != baseline.dateFormat) draft.dateFormat else loaded.dateFormat,
        autoLight = if (draft.autoLight != baseline.autoLight) draft.autoLight else loaded.autoLight,
        lightDuration = if (draft.lightDuration != baseline.lightDuration) draft.lightDuration else loaded.lightDuration,
        powerSavingMode = if (draft.powerSavingMode != baseline.powerSavingMode) draft.powerSavingMode else loaded.powerSavingMode,
        buttonTone = if (draft.buttonTone != baseline.buttonTone) draft.buttonTone else loaded.buttonTone,
        keyVibration = if (draft.keyVibration != baseline.keyVibration) draft.keyVibration else loaded.keyVibration,
        timeAdjustment = if (draft.timeAdjustment != baseline.timeAdjustment) draft.timeAdjustment else loaded.timeAdjustment,
        adjustmentTimeMinutes = if (draft.adjustmentTimeMinutes != baseline.adjustmentTimeMinutes) draft.adjustmentTimeMinutes else loaded.adjustmentTimeMinutes,
        font = if (draft.font != baseline.font) draft.font else loaded.font,
    )
}

/** Apply only the fields changed by this control, preserving all other API settings. */
internal fun applySettingsEdit(settings: Settings, previous: Setting, updated: Setting): Settings = settings.copy().apply {
    when {
        previous is SettingsViewModel.Locale && updated is SettingsViewModel.Locale -> {
            if (previous.dayOfWeekLanguage != updated.dayOfWeekLanguage) language = updated.dayOfWeekLanguage.englishName
            if (previous.timeFormat != updated.timeFormat) timeFormat = updated.timeFormat.value
            if (previous.dateFormat != updated.dateFormat) dateFormat = updated.dateFormat.value
        }
        previous is SettingsViewModel.Light && updated is SettingsViewModel.Light -> {
            if (previous.autoLight != updated.autoLight) autoLight = updated.autoLight
            if (previous.duration != updated.duration) lightDuration = updated.duration.value
        }
        previous is SettingsViewModel.OperationSound && updated is SettingsViewModel.OperationSound -> {
            if (previous.sound != updated.sound) buttonTone = updated.sound
            if (previous.vibrate != updated.vibrate) keyVibration = updated.vibrate
        }
        previous is SettingsViewModel.PowerSavingMode && updated is SettingsViewModel.PowerSavingMode -> {
            if (previous.powerSavingMode != updated.powerSavingMode) powerSavingMode = updated.powerSavingMode
        }
        previous is SettingsViewModel.TimeAdjustment && updated is SettingsViewModel.TimeAdjustment -> {
            if (previous.timeAdjustment != updated.timeAdjustment) timeAdjustment = updated.timeAdjustment
            if (previous.adjustmentTimeMinutes != updated.adjustmentTimeMinutes) adjustmentTimeMinutes = updated.adjustmentTimeMinutes
        }
        previous is SettingsViewModel.Font && updated is SettingsViewModel.Font -> {
            if (previous.font != updated.font) font = updated.font.value
        }
    }
}

internal fun settingsReloads(
    selected: Flow<String?>,
    initialized: Flow<WatchConnectionSession.Ready?>,
): Flow<Pair<String?, WatchConnectionSession.Ready?>> = combine(selected, initialized) { addr, ready ->
    addr to ready?.takeIf { it.address == addr }
}.distinctUntilChanged()
