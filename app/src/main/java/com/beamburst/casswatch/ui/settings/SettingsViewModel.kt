package com.beamburst.casswatch.ui.settings

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
import com.beamburst.casswatch.ui.alarms.applyHourlySignalToWatch
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
        val settingsMap: Map<Class<out Setting>, Setting> = emptyMap()
)

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
        viewModelScope.launch(Dispatchers.Default) {
            watchSelector.selected.collect { addr ->
                runCatching {
                    loadSettingsForAddress(addr)
                }.onFailure { Timber.w(it, "Failed to load settings for %s", addr) }
            }
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch(Dispatchers.Default) {
            combine(watchSelector.selected, connectionTracker.states) { addr, _ ->
                isSelectedWatchConnected(addr)
            }.collect { connected ->
                _isConnected.value = connected
            }
        }
    }

    private suspend fun loadSettingsForAddress(addr: String?) {
        updateSettingsAndMap(defaultSettings())
        loadAutoSyncOnReconnectForAddress(addr)
        val cached = if (addr != null) WatchSettingsCache.load(appContext, addr) else null
        if (cached != null) {
            updateSettingsAndMap(fromJson(Gson().toJson(cached)))
            return
        }

        if (!isSelectedWatchConnected(addr)) return

        val ready = connectionSession.matching(addr) ?: return
        val settings = api.getSettings()
        if (!connectionSession.isCurrent(ready)) return
        if (addr != null) {
            WatchSettingsCache.saveClean(appContext, addr, settings)
        }
        val settingStr = Gson().toJson(settings)
        updateSettingsAndMap(fromJson(settingStr))
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
        val currentList = state.value.settings.toMutableList()
        val index = currentList.indexOfFirst { it::class == updatedSetting::class }
        if (index != -1) {
            currentList[index] = updatedSetting
            val newMap = state.value.settingsMap.toMutableMap()
            newMap[updatedSetting::class.java] = updatedSetting
            _state.update { it.copy(settings = currentList, settingsMap = newMap) }
            val address = watchSelector.currentAddress() ?: return
            val settings = buildApiSettings()
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
        viewModelScope.launch {
            writeGuard.run(ready) { checkSession ->
                WatchSyncActivity.track {
                    val watchTime = watchTimeProvider.nowForWatch(ready.address)
                    watchTime.fallbackReason?.let { AppSnackbar(it) }
                    val addr = ready.address
                    val signalSettings = HourlySignalSettings.load(appContext, addr)
                    val now = LocalDateTime.now()
                    val hourlyTarget = HourlySignalEvaluator.shouldChimeAt(now, signalSettings)
                    var writeResult: TimeWriteResult? = null
                    val result = runCatching {
                        writeResult = watchTimeWriter.write(api, watchTime, ready, checkSession)
                        checkSession()
                        applyHourlySignalToWatch(api, signalSettings, now)
                    }
                    checkSession()
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
                        AppSnackbar(result.exceptionOrNull()?.message ?: appContext.getString(R.string.watch_not_connected))
                    }
                }
            }
        }
    }

    fun sendToWatch() {
        val ready = writeGuard.capture() ?: return
        val settings = buildApiSettings()

        viewModelScope.launch {
            writeGuard.run(ready) { checkSession ->
                WatchSyncActivity.track {
                    val result = runCatching {
                        checkSession()
                        api.setSettings(settings)
                        checkSession()
                        WatchSettingsCache.saveClean(appContext, ready.address, settings)
                        AppSnackbar(appContext.getString(R.string.settings_sent_to_watch))
                    }
                    checkSession()
                    syncLogger.record(
                        address = ready.address,
                        trigger = SyncTrigger.MANUAL_SEND_SETTINGS,
                        kinds = setOf(SyncKind.SETTINGS),
                        success = result.isSuccess,
                        errorMessage = result.exceptionOrNull()?.message,
                        details = SyncDetails.Empty.copy(actions = listOf("Watch settings sent")),
                    )
                    result.onFailure { e -> AppSnackbar(e.message ?: appContext.getString(R.string.api_error)) }
                }
            }
        }
    }

    private fun buildApiSettings(): Settings {
        val settings = Settings()

        val localeSetting: Locale = getSetting(Locale::class.java)
        settings.language = localeSetting.dayOfWeekLanguage.englishName
        settings.timeFormat = localeSetting.timeFormat.value
        settings.dateFormat = localeSetting.dateFormat.value

        val lightSetting: Light = getSetting(Light::class.java)
        settings.autoLight = lightSetting.autoLight
        settings.lightDuration = lightSetting.duration.value

        (state.value.settingsMap[PowerSavingMode::class.java] as? PowerSavingMode)?.let {
            settings.powerSavingMode = it.powerSavingMode
        }

        val buttonTone: OperationSound = getSetting(OperationSound::class.java)
        settings.buttonTone = buttonTone.sound
        settings.keyVibration = buttonTone.vibrate

        val timeAdjustment: TimeAdjustment = getSetting(TimeAdjustment::class.java)
        settings.timeAdjustment = timeAdjustment.timeAdjustment
        settings.adjustmentTimeMinutes = timeAdjustment.adjustmentTimeMinutes

        (state.value.settingsMap[Font::class.java] as? Font)?.let {
            settings.font = it.font.value
        }

        return settings
    }

    private fun isSelectedWatchConnected(addr: String?): Boolean =
        api.isConnected() && connectionSession.matching(addr) != null

}
