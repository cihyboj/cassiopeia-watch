package com.beamburst.casswatch.ui.debug

import android.content.Context
import com.beamburst.casswatch.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.Utils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.AppNotification
import org.avmedia.gshockapi.NotificationType
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ble.Connection
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.ble.IDataReceived
import org.avmedia.gshockapi.casio.MessageDispatcher
import org.avmedia.gshockapi.io.IO
import timber.log.Timber

data class BleDebugLogRow(
	val id: Long,
	val timestamp: String,
	val label: String,
	val hex: String,
	val result: String,
)

data class BleDebugUiState(
	val connected: Boolean = false,
	val deviceName: String = "",
	val deviceAddress: String = "",
	val serviceFound: Boolean = false,
	val commandCharacteristicFound: Boolean = false,
	val readCharacteristicFound: Boolean = false,
	val notificationCharacteristicFound: Boolean = false,
	val manualHex: String = "",
	val manualLabel: String = "",
	val dryRun: Boolean = false,
	val manualValidationError: String? = null,
	val notificationApp: String = "",
	val notificationTitle: String = "",
	val notificationText: String = "",
	val customClassHex: String = "07",
	val customPayloadHex: String = "",
	val customValidationError: String? = null,
	val generatedCustomHex: String = "07",
	val repeatIntervalSeconds: Int = 5,
	val repeating: Boolean = false,
	val lastCommandLabel: String? = null,
	val lastCommandHex: String? = null,
	val logRows: List<BleDebugLogRow> = emptyList(),
)

@HiltViewModel
class BleDebugViewModel @Inject constructor(
	private val repository: GShockRepository,
	private val selector: CurrentWatchSelector,
	private val tracker: WatchConnectionTracker,
	@param:ApplicationContext private val appContext: Context,
) : ViewModel() {
	private val _state = MutableStateFlow(
        BleDebugUiState(
            notificationApp = appContext.getString(R.string.ble_debug_default_app),
            notificationTitle = appContext.getString(R.string.ble_debug_default_title),
            notificationText = appContext.getString(R.string.ble_debug_default_text),
        )
    )
	val state: StateFlow<BleDebugUiState> = _state.asStateFlow()

	private val timestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
	private val notificationTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US)
	private val subscriberId = "BleDebugViewModel-${System.identityHashCode(this)}"
	private val nextLogId = AtomicLong(0L)
	private var repeatJob: Job? = null
	private var notificationLoggingActive = false

	init {
		observeConnectionStatus()
		subscribeProgressEvents()
	}

	fun updateManualHex(value: String) {
		_state.update { it.copy(manualHex = value, manualValidationError = null) }
	}

	fun updateManualLabel(value: String) {
		_state.update { it.copy(manualLabel = value) }
	}

	fun updateDryRun(value: Boolean) {
		_state.update { it.copy(dryRun = value) }
	}

	fun updateNotificationApp(value: String) {
		_state.update { it.copy(notificationApp = value) }
	}

	fun updateNotificationTitle(value: String) {
		_state.update { it.copy(notificationTitle = value) }
	}

	fun updateNotificationText(value: String) {
		_state.update { it.copy(notificationText = value) }
	}

	fun updateCustomClass(value: String) {
		_state.update { it.copy(customClassHex = sanitizeClassInput(value), customValidationError = null) }
		updateCustomPreview()
	}

	fun updateCustomPayload(value: String) {
		_state.update { it.copy(customPayloadHex = value, customValidationError = null) }
		updateCustomPreview()
	}

	fun updateRepeatInterval(seconds: Int) {
		_state.update { it.copy(repeatIntervalSeconds = seconds) }
		if (state.value.repeating) startRepeat()
	}

	fun sendManual() {
		val current = state.value
		val label = current.manualLabel.ifBlank { appContext.getString(R.string.ble_debug_manual_command) }
		sendHex(label = label, hex = current.manualHex, dryRun = current.dryRun) { error ->
			_state.update { it.copy(manualValidationError = error) }
		}
	}

	fun sendPreset(label: String, hex: String) {
		sendHex(label = label, hex = hex, dryRun = state.value.dryRun)
	}

	fun sendReadRequest(label: String, hex: String) {
		sendHex(
			label = label,
			hex = hex,
			dryRun = state.value.dryRun,
			mode = GetSetMode.GET,
			targetDescription = "26eb002c",
		)
	}

	fun sendAppNotificationPreset(label: String, type: NotificationType) {
		val current = state.value
		val app = current.notificationApp.ifBlank { appContext.getString(R.string.ble_debug_cassiopeia) }
		val title = current.notificationTitle.ifBlank { label }
		val text = current.notificationText.ifBlank { appContext.getString(R.string.ble_debug_ble_debug_notification_test) }
		val notification = AppNotification(
			type = type,
			timestamp = LocalDateTime.now().format(notificationTimestampFormatter),
			app = app,
			title = title,
			text = text,
			shortText = text.take(18),
		)

		if (current.dryRun) {
			appendLog(label, "AppNotification:${type.name}", appContext.getString(R.string.ble_debug_dry_run_only))
			return
		}
		if (!repository.isConnected()) {
			appendLog(label, "AppNotification:${type.name}", appContext.getString(R.string.ble_debug_not_connected))
			return
		}
		if (!repository.supportsAppNotifications()) {
			appendLog(label, "AppNotification:${type.name}", appContext.getString(R.string.ble_debug_notification_characteristic_is_not_available))
			return
		}

		runCatching {
			repository.sendAppNotification(notification)
		}.onSuccess {
			appendLog(label, "AppNotification:${type.name}", appContext.getString(R.string.ble_debug_queued_app_notification_write))
			refreshStatus()
		}.onFailure { error ->
			appendLog(label, "AppNotification:${type.name}", appContext.getString(R.string.ble_debug_notification_error, error.message ?: error::class.java.simpleName))
		}
	}

	fun sendCustom() {
		updateCustomPreview()
		val current = state.value
		sendHex(
			label = appContext.getString(R.string.ble_debug_custom_class_label, current.customClassHex.uppercase(Locale.US)),
			hex = current.generatedCustomHex,
			dryRun = current.dryRun,
		) { error ->
			_state.update { it.copy(customValidationError = error) }
		}
	}

	fun clearLog() {
		_state.update { it.copy(logRows = emptyList()) }
	}

	fun startRepeat() {
		stopRepeat()
		val current = state.value
		val hex = current.lastCommandHex
		val label = current.lastCommandLabel
		if (hex.isNullOrBlank() || label.isNullOrBlank()) {
			appendLog(appContext.getString(R.string.ble_debug_repeat), "", appContext.getString(R.string.ble_debug_no_command_has_been_sent_yet))
			return
		}

		_state.update { it.copy(repeating = true) }
		repeatJob = viewModelScope.launch {
			while (isActive) {
				delay(state.value.repeatIntervalSeconds * 1000L)
				sendHex(appContext.getString(R.string.ble_debug_repeat_label, label), hex, state.value.dryRun, rememberAsLastCommand = false)
			}
		}
	}

	fun stopRepeat() {
		repeatJob?.cancel()
		repeatJob = null
		_state.update { it.copy(repeating = false) }
	}

	fun logText(): String =
		state.value.logRows.joinToString(separator = "\n") { row ->
			"${row.timestamp}\t${row.label}\t${row.hex}\t${row.result}"
		}

	fun startNotificationLogging() {
		notificationLoggingActive = true
		installNotificationLogger()
	}

	fun stopNotificationLogging() {
		notificationLoggingActive = false
		restoreDefaultNotificationDispatcher()
	}

	private fun sendHex(
		label: String,
		hex: String,
		dryRun: Boolean,
		rememberAsLastCommand: Boolean = true,
		mode: GetSetMode = GetSetMode.SET,
		targetDescription: String = "26eb002d",
		onValidationError: (String?) -> Unit = {},
	) {
		val bytes = HexFormat.parse(hex).getOrElse { error ->
			val message = validationMessage(error)
			onValidationError(message)
			appendLog(label, hex, message)
			return
		}
		val formattedHex = HexFormat.format(bytes)
		onValidationError(null)

		if (rememberAsLastCommand) {
			_state.update {
				it.copy(
					lastCommandLabel = label,
					lastCommandHex = formattedHex,
				)
			}
		}

		if (dryRun) {
			appendLog(label, formattedHex, appContext.getString(R.string.ble_debug_dry_run_only))
			return
		}
		if (!repository.isConnected()) {
			appendLog(label, formattedHex, appContext.getString(R.string.ble_debug_not_connected))
			return
		}

		runCatching {
			IO.writeCmd(mode, bytes)
		}.onSuccess {
			appendLog(label, formattedHex, appContext.getString(R.string.ble_debug_queued_write, mode.name.lowercase(Locale.US), targetDescription))
			refreshStatus()
		}.onFailure { error ->
			appendLog(label, formattedHex, appContext.getString(R.string.ble_debug_write_error, error.message ?: error::class.java.simpleName))
		}
	}

	private fun observeConnectionStatus() {
		viewModelScope.launch {
			combine(selector.selected, tracker.states) { selected, states ->
				selected to states
			}.collect { (selected, states) ->
				refreshStatus(selected, states)
			}
		}
		refreshStatus()
	}

	private fun subscribeProgressEvents() {
		ProgressEvents.runEventActions(
			subscriberId,
			arrayOf(
				safeEventAction("WatchInitializationCompleted") {
					appendLog(appContext.getString(R.string.ble_debug_connection), "", appContext.getString(R.string.ble_debug_watch_initialization_completed))
					refreshStatus()
					if (notificationLoggingActive) installNotificationLogger()
				},
				safeEventAction("ConnectionSetupComplete") {
					appendLog(appContext.getString(R.string.ble_debug_connection), "", appContext.getString(R.string.ble_debug_connection_setup_complete))
					refreshStatus()
					if (notificationLoggingActive) installNotificationLogger()
				},
				safeEventAction("NotificationsEnabled") {
					appendLog(appContext.getString(R.string.ble_debug_connection), "", appContext.getString(R.string.ble_debug_notifications_enabled))
					refreshStatus()
				},
				safeEventAction("Disconnect") {
					appendLog(appContext.getString(R.string.ble_debug_connection), "", appContext.getString(R.string.ble_debug_disconnected))
					refreshStatus()
				},
				safeEventAction("DeviceDisappeared") {
					appendLog(appContext.getString(R.string.ble_debug_connection), "", appContext.getString(R.string.ble_debug_device_disappeared))
					refreshStatus()
				},
				safeEventAction("ConnectionFailed") {
					appendLog(appContext.getString(R.string.ble_debug_connection), "", appContext.getString(R.string.ble_debug_connection_failed))
					refreshStatus()
				},
				safeEventAction("ApiError") {
					appendLog(appContext.getString(R.string.ble_debug_api_error), "", ProgressEvents.getPayload("ApiError")?.toString().orEmpty())
					refreshStatus()
				},
				safeEventAction("Error") {
					appendLog(appContext.getString(R.string.ble_debug_error), "", ProgressEvents.getPayload("Error")?.toString().orEmpty())
					refreshStatus()
				},
			)
		)
	}

	private fun safeEventAction(name: String, block: () -> Unit): EventAction =
		EventAction(name) {
			runCatching(block).onFailure { Timber.e(it, "BLE debug event %s failed", name) }
		}

	private fun installNotificationLogger() {
		runCatching {
			Connection.setDataCallback(
				object : IDataReceived {
					override fun dataReceived(data: String?) {
						val value = data.orEmpty()
						appendLog(appContext.getString(R.string.ble_debug_notify_26eb002d), normalizeNotifyHex(value), appContext.getString(R.string.ble_debug_received))
						runCatching { MessageDispatcher.onReceived(value) }
							.onFailure { Timber.w(it, "BLE notification dispatch failed") }
					}
				}
			)
		}.onFailure { error ->
			appendLog(appContext.getString(R.string.ble_debug_notify_logger), "", appContext.getString(R.string.ble_debug_unavailable, error.message ?: error::class.java.simpleName))
		}
	}

	private fun restoreDefaultNotificationDispatcher() {
		runCatching {
			Connection.setDataCallback(
				object : IDataReceived {
					override fun dataReceived(data: String?) {
						runCatching { MessageDispatcher.onReceived(data.orEmpty()) }
							.onFailure { Timber.w(it, "BLE notification dispatch failed") }
					}
				}
			)
		}
	}

	private fun refreshStatus(
		selectedAddress: String? = selector.currentAddress(),
		states: Map<String, ConnectionState> = tracker.states.value,
	) {
		viewModelScope.launch(Dispatchers.IO) {
			runCatching {
				val normalizedAddress = selectedAddress?.let(Utils::sanitizeMacAddress).orEmpty()
				val trackedState = states[normalizedAddress]
					?: states.entries.firstOrNull { it.key.equals(normalizedAddress, ignoreCase = true) }?.value
				val connected = trackedState == ConnectionState.CONNECTED ||
					(trackedState == null && repository.isConnected())
				val readSupported = isServiceSupported(GetSetMode.GET)
				val writeSupported = isServiceSupported(GetSetMode.SET)
				val notifySupported = isServiceSupported(GetSetMode.NOTIFY)

				_state.update {
					it.copy(
						connected = connected,
						deviceName = resolveWatchName(normalizedAddress),
						deviceAddress = normalizedAddress,
						serviceFound = readSupported || writeSupported,
						commandCharacteristicFound = writeSupported,
						readCharacteristicFound = readSupported,
						notificationCharacteristicFound = notifySupported,
					)
				}
			}.onFailure { Timber.w(it, "Failed to refresh BLE debug status") }
		}
	}

	private fun isServiceSupported(mode: GetSetMode): Boolean =
		runCatching { Connection.isServiceSupported(mode) }.getOrDefault(false)

	private fun resolveWatchName(address: String): String {
		WatchInfo.name
			?.takeIf { it.isNotBlank() }
			?.let { return it }
		if (address.isNotBlank()) {
			LocalDataStorage.getDeviceName(appContext, address)
				?.takeIf { it.isNotBlank() }
				?.let { return it }
		}
		return appContext.getString(R.string.ble_debug_unknown)
	}

	private fun updateCustomPreview() {
		val current = state.value
		val classResult = HexFormat.normalize(current.customClassHex)
		val payloadResult = if (current.customPayloadHex.isBlank()) {
			Result.success("")
		} else {
			HexFormat.normalize(current.customPayloadHex)
		}
		val error = (classResult.exceptionOrNull() ?: payloadResult.exceptionOrNull())?.let(::validationMessage)
		_state.update {
			it.copy(
				customValidationError = error,
				generatedCustomHex = classResult.getOrDefault(current.customClassHex.uppercase(Locale.US)) +
					payloadResult.getOrDefault(current.customPayloadHex.filterNot(Char::isWhitespace).uppercase(Locale.US)),
			)
		}
	}

	private fun validationMessage(error: Throwable): String =
		appContext.getString(
			when ((error as? HexFormat.ValidationException)?.reason) {
				HexFormat.ValidationError.REQUIRED -> R.string.ble_debug_hex_required
				HexFormat.ValidationError.ODD_LENGTH -> R.string.ble_debug_hex_odd_length
				HexFormat.ValidationError.NON_HEX -> R.string.ble_debug_hex_non_hex
				null -> R.string.ble_debug_invalid_hex_value
			}
		)

	private fun normalizeNotifyHex(data: String): String =
		data.removePrefix("0x").removePrefix("0X").uppercase(Locale.US)

	private fun sanitizeClassInput(value: String): String {
		val compact = value
			.filterNot { it.isWhitespace() }
			.removePrefix("0x")
			.removePrefix("0X")
		return compact.take(2)
	}

	private fun appendLog(label: String, hex: String, result: String) {
		_state.update { current ->
			current.copy(
				logRows = (
					listOf(
						BleDebugLogRow(
							id = nextLogId.getAndIncrement(),
							timestamp = LocalTime.now().format(timestampFormatter),
							label = label,
							hex = hex,
							result = result.ifBlank { appContext.getString(R.string.ble_debug_ok) },
						)
					) + current.logRows
				).take(200)
			)
		}
	}

	override fun onCleared() {
		stopRepeat()
		ProgressEvents.subscriber.stop(subscriberId)
		stopNotificationLogging()
		super.onCleared()
	}
}
