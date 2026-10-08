package com.beamburst.casswatch.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.beamburst.casswatch.R
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.ScreenTitle
import org.avmedia.gshockapi.NotificationType

private data class DebugPreset(
	@StringRes val label: Int,
	val hex: String,
)

private val alarmPresets = listOf(
	DebugPreset(R.string.ble_debug_alarm_1_off_07_00, "1500400700"),
	DebugPreset(R.string.ble_debug_alarm_1_on_07_00, "1540400700"),
	DebugPreset(R.string.ble_debug_alarm_1_on_08_00, "1540400800"),
	DebugPreset(R.string.ble_debug_alarm_1_on_09_00, "1540400900"),
	DebugPreset(R.string.ble_debug_alarm_1_one_time_07_00, "1560400700"),
)

private val buzzerPresets = listOf(
	DebugPreset(R.string.ble_debug_buzzer_start_alarm, "4c0001"),
	DebugPreset(R.string.ble_debug_buzzer_stop_alarm, "4c0201"),
	DebugPreset(R.string.ble_debug_buzzer_start_timer, "4c0000"),
	DebugPreset(R.string.ble_debug_buzzer_stop_timer, "4c0200"),
	DebugPreset(R.string.ble_debug_buzzer_start_auto_lap, "4c0002"),
	DebugPreset(R.string.ble_debug_buzzer_stop_auto_lap, "4c0202"),
)

private val phoneFinderPresets = listOf(
	DebugPreset(R.string.ble_debug_no_alert, "0100"),
	DebugPreset(R.string.ble_debug_mild_alert, "0101"),
	DebugPreset(R.string.ble_debug_high_alert, "0102"),
)

private val bleTimeoutPresets = listOf(
	DebugPreset(R.string.ble_debug_timeout_3_min, "110F0F0F0600500004000100001E03"),
	DebugPreset(R.string.ble_debug_timeout_5_min, "110F0F0F0600500004000100001E05"),
	DebugPreset(R.string.ble_debug_timeout_10_min, "110F0F0F0600500004000100001E0A"),
	DebugPreset(R.string.ble_debug_timeout_60_min, "110F0F0F0600500004000100001E3C"),
	DebugPreset(R.string.ble_debug_timeout_90_min, "110F0F0F0600500004000100001E5A"),
	DebugPreset(R.string.ble_debug_timeout_120_min, "110F0F0F0600500004000100001E78"),
)

private val legacyAlertNotificationPresets = listOf(
	DebugPreset(R.string.ble_debug_new_alert_email, "070101454D41494C2054455354"),
	DebugPreset(R.string.ble_debug_new_alert_call, "07030143414C4C2054455354"),
	DebugPreset(R.string.ble_debug_new_alert_sms, "070501534D532054455354"),
	DebugPreset(R.string.ble_debug_new_alert_schedule, "07070143414C2054455354"),
	DebugPreset(R.string.ble_debug_new_alert_sns, "070D01534E532054455354"),
	DebugPreset(R.string.ble_debug_more_alert_test, "0E0000002E202054455354"),
	DebugPreset(R.string.ble_debug_more_alert_abcdef, "0E0000002E414243444546"),
	DebugPreset(R.string.ble_debug_more_alert_clear, "0E00000000"),
	DebugPreset(R.string.ble_debug_long_alert_music, "0F000A0154455354204D55534943"),
	DebugPreset(R.string.ble_debug_long_alert_sns, "0F000D015445535420534E53"),
	DebugPreset(R.string.ble_debug_long_alert_email, "0F0001015445535420454D41494C"),
	DebugPreset(R.string.ble_debug_long_alert_schedule, "0F000701544553542043414C"),
)

private val stepReadPresets = listOf(
	DebugPreset(R.string.ble_debug_read_step_class, "4d"),
	DebugPreset(R.string.ble_debug_read_step_unit_0, "4d00"),
	DebugPreset(R.string.ble_debug_read_step_unit_1, "4d01"),
	DebugPreset(R.string.ble_debug_read_watch_condition, "28"),
)

private val notificationReadPresets = listOf(
	DebugPreset(R.string.ble_debug_read_ble_settings, "11"),
	DebugPreset(R.string.ble_debug_read_notification_manager, "3d30"),
	DebugPreset(R.string.ble_debug_read_custom_alert, "3f"),
	DebugPreset(R.string.ble_debug_read_notification_method, "42"),
)

private val featureDiscoveryReadPresets = listOf(
	DebugPreset(R.string.ble_debug_read_timer, "18"),
	DebugPreset(R.string.ble_debug_read_stopwatch, "1c"),
	DebugPreset(R.string.ble_debug_read_version_info, "20"),
	DebugPreset(R.string.ble_debug_read_watch_condition, "28"),
	DebugPreset(R.string.ble_debug_read_module_id, "26"),
	DebugPreset(R.string.ble_debug_read_app_info, "22"),
	DebugPreset(R.string.ble_debug_read_watch_name, "23"),
	DebugPreset(R.string.ble_debug_read_location_radio, "24"),
)

private val notificationClasses = listOf("07", "0c", "0d", "0e", "0f", "3d", "3f", "42")

private data class NotificationPreset(
	@StringRes val label: Int,
	val type: NotificationType,
)

private val displayNotificationPresets = listOf(
	NotificationPreset(R.string.ble_debug_generic, NotificationType.GENERIC),
	NotificationPreset(R.string.ble_debug_message, NotificationType.MESSAGE),
	NotificationPreset(R.string.ble_debug_email, NotificationType.EMAIL),
	NotificationPreset(R.string.ble_debug_calendar, NotificationType.CALENDAR),
	NotificationPreset(R.string.ble_debug_phone_call, NotificationType.PHONE_CALL),
	NotificationPreset(R.string.ble_debug_urgent_call, NotificationType.PHONE_CALL_URGENT),
	NotificationPreset(R.string.ble_debug_email_sms, NotificationType.EMAIL_SMS),
)

@Composable
fun BleDebugScreen(
	viewModel: BleDebugViewModel = hiltViewModel(),
) {
	val state by viewModel.state.collectAsState()
	val clipboard = LocalClipboardManager.current

	DisposableEffect(Unit) {
		viewModel.startNotificationLogging()
		onDispose {
			viewModel.stopRepeat()
			viewModel.stopNotificationLogging()
		}
	}

	Column {
		ScreenTitle(
			text = stringResource(R.string.ble_debug_ble_debug),
			modifier = Modifier,
		)
		LazyColumn(
			contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
			verticalArrangement = Arrangement.spacedBy(Spacing.sm),
		) {
			item {
				ConnectionStatusCard(state)
			}
			item {
				ManualCommandCard(
					state = state,
					onHexChange = viewModel::updateManualHex,
					onLabelChange = viewModel::updateManualLabel,
					onDryRunChange = viewModel::updateDryRun,
					onSend = viewModel::sendManual,
					onClear = viewModel::clearLog,
				)
			}
			item {
				PresetSection(stringResource(R.string.ble_debug_alarm), alarmPresets, viewModel::sendPreset)
			}
			item {
				PresetSection(stringResource(R.string.ble_debug_buzzer), buzzerPresets, viewModel::sendPreset)
			}
			item {
				PresetSection(stringResource(R.string.ble_debug_phone_finder_immediate_alert), phoneFinderPresets, viewModel::sendPreset)
			}
			item {
				PresetSection(stringResource(R.string.ble_debug_ble_connection_timeout), bleTimeoutPresets, viewModel::sendPreset)
			}
			item {
				ReadProbeSection(stringResource(R.string.ble_debug_step_counter_read_probes), stepReadPresets, viewModel::sendReadRequest)
			}
			item {
				ReadProbeSection(stringResource(R.string.ble_debug_notification_config_read_probes), notificationReadPresets, viewModel::sendReadRequest)
			}
			item {
				ReadProbeSection(stringResource(R.string.ble_debug_feature_discovery_read_probes), featureDiscoveryReadPresets, viewModel::sendReadRequest)
			}
			item {
				DisplayNotificationCard(
					state = state,
					onAppChange = viewModel::updateNotificationApp,
					onTitleChange = viewModel::updateNotificationTitle,
					onTextChange = viewModel::updateNotificationText,
					onSend = viewModel::sendAppNotificationPreset,
				)
			}
			item {
				PresetSection(stringResource(R.string.ble_debug_casio_alert_notification_probes), legacyAlertNotificationPresets, viewModel::sendPreset)
			}
			item {
				NotificationExperimentCard(
					selectedClass = state.customClassHex,
					onClassSelected = viewModel::updateCustomClass,
				)
			}
			item {
				CustomClassCard(
					state = state,
					onClassChange = viewModel::updateCustomClass,
					onPayloadChange = viewModel::updateCustomPayload,
					onSend = viewModel::sendCustom,
				)
			}
			item {
				RepeatCard(
					state = state,
					onIntervalSelected = viewModel::updateRepeatInterval,
					onStart = viewModel::startRepeat,
					onStop = viewModel::stopRepeat,
				)
			}
			item {
				LogHeader(
					onCopy = {
						clipboard.setText(AnnotatedString(viewModel.logText()))
					},
					onClear = viewModel::clearLog,
				)
			}
			items(
				items = state.logRows,
				key = { it.id },
			) { row ->
				LogRow(row)
			}
		}
	}
}

@Composable
private fun ConnectionStatusCard(state: BleDebugUiState) {
	DebugCard(title = stringResource(R.string.ble_debug_connection_status)) {
		StatusLine(stringResource(R.string.ble_debug_connected), if (state.connected) stringResource(R.string.ble_debug_yes) else stringResource(R.string.ble_debug_no))
		StatusLine(stringResource(R.string.ble_debug_device), state.deviceName.ifBlank { stringResource(R.string.ble_debug_unknown) })
		StatusLine(stringResource(R.string.ble_debug_address), state.deviceAddress.ifBlank { stringResource(R.string.ble_debug_unknown) })
		StatusLine(stringResource(R.string.ble_debug_service_26eb000d), if (state.serviceFound) stringResource(R.string.ble_debug_yes) else stringResource(R.string.ble_debug_no))
		StatusLine(stringResource(R.string.ble_debug_command_26eb002d), if (state.commandCharacteristicFound) stringResource(R.string.ble_debug_yes) else stringResource(R.string.ble_debug_no))
		StatusLine(stringResource(R.string.ble_debug_read_request_26eb002c), if (state.readCharacteristicFound) stringResource(R.string.ble_debug_yes) else stringResource(R.string.ble_debug_no))
		StatusLine(stringResource(R.string.ble_debug_notification_writer), if (state.notificationCharacteristicFound) stringResource(R.string.ble_debug_yes) else stringResource(R.string.ble_debug_no))
	}
}

@Composable
private fun ManualCommandCard(
	state: BleDebugUiState,
	onHexChange: (String) -> Unit,
	onLabelChange: (String) -> Unit,
	onDryRunChange: (Boolean) -> Unit,
	onSend: () -> Unit,
	onClear: () -> Unit,
) {
	DebugCard(title = stringResource(R.string.ble_debug_manual_command_sender)) {
		OutlinedTextField(
			value = state.manualLabel,
			onValueChange = onLabelChange,
			label = { AppText(stringResource(R.string.ble_debug_optional_label)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = state.manualHex,
			onValueChange = onHexChange,
			label = { AppText(stringResource(R.string.ble_debug_hex_input)) },
			isError = state.manualValidationError != null,
			supportingText = {
				state.manualValidationError?.let { AppText(it, color = MaterialTheme.colorScheme.error) }
			},
			keyboardOptions = hexKeyboardOptions(),
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
			modifier = Modifier.fillMaxWidth(),
		) {
			Checkbox(checked = state.dryRun, onCheckedChange = onDryRunChange)
			AppText(stringResource(R.string.ble_debug_dry_run), modifier = Modifier.weight(1f))
			OutlinedButton(onClick = onClear) {
				Icon(Icons.Filled.Delete, contentDescription = null)
				AppText(stringResource(R.string.ble_debug_clear_log))
			}
			Button(onClick = onSend) {
				Icon(Icons.Filled.Send, contentDescription = null)
				AppText(stringResource(R.string.ble_debug_send))
			}
		}
	}
}

@Composable
private fun PresetSection(
	title: String,
	presets: List<DebugPreset>,
	onSend: (String, String) -> Unit,
) {
	DebugCard(title = title) {
		presets.chunked(2).forEach { row ->
			Row(
				horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
				modifier = Modifier.fillMaxWidth(),
			) {
				row.forEach { preset ->
					val presetLabel = stringResource(preset.label)
					ElevatedButton(
						onClick = { onSend(presetLabel, preset.hex) },
						modifier = Modifier.weight(1f),
					) {
						Column(horizontalAlignment = Alignment.Start) {
							AppText(
								text = presetLabel,
								style = MaterialTheme.typography.labelLarge,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
							AppText(
								text = preset.hex.uppercase(),
								style = MaterialTheme.typography.labelSmall,
								fontFamily = FontFamily.Monospace,
							)
						}
					}
				}
				if (row.size == 1) {
					Column(modifier = Modifier.weight(1f)) {}
				}
			}
		}
	}
}

@Composable
private fun ReadProbeSection(
	title: String,
	presets: List<DebugPreset>,
	onRead: (String, String) -> Unit,
) {
	DebugCard(title = title) {
		AppText(
			text = stringResource(R.string.ble_debug_sends_raw_read_requests_to_26eb002c_watch_responses_or_errors_should_appear),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		presets.chunked(2).forEach { row ->
			Row(
				horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
				modifier = Modifier.fillMaxWidth(),
			) {
				row.forEach { preset ->
					val presetLabel = stringResource(preset.label)
					ElevatedButton(
						onClick = { onRead(presetLabel, preset.hex) },
						modifier = Modifier.weight(1f),
					) {
						Column(horizontalAlignment = Alignment.Start) {
							AppText(
								text = presetLabel,
								style = MaterialTheme.typography.labelLarge,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
							AppText(
								text = preset.hex.uppercase(),
								style = MaterialTheme.typography.labelSmall,
								fontFamily = FontFamily.Monospace,
							)
						}
					}
				}
				if (row.size == 1) {
					Column(modifier = Modifier.weight(1f)) {}
				}
			}
		}
	}
}

@Composable
private fun DisplayNotificationCard(
	state: BleDebugUiState,
	onAppChange: (String) -> Unit,
	onTitleChange: (String) -> Unit,
	onTextChange: (String) -> Unit,
	onSend: (String, NotificationType) -> Unit,
) {
	DebugCard(title = stringResource(R.string.ble_debug_display_app_notification)) {
		AppText(
			text = stringResource(R.string.ble_debug_uses_the_app_notification_encoder_and_notification_characteristic_not_a_gue),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		OutlinedTextField(
			value = state.notificationApp,
			onValueChange = onAppChange,
			label = { AppText(stringResource(R.string.ble_debug_app_label)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = state.notificationTitle,
			onValueChange = onTitleChange,
			label = { AppText(stringResource(R.string.ble_debug_title)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = state.notificationText,
			onValueChange = onTextChange,
			label = { AppText(stringResource(R.string.ble_debug_text)) },
			minLines = 2,
			maxLines = 3,
			modifier = Modifier.fillMaxWidth(),
		)
		displayNotificationPresets.chunked(2).forEach { row ->
			Row(
				horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
				modifier = Modifier.fillMaxWidth(),
			) {
				row.forEach { preset ->
					val presetLabel = stringResource(preset.label)
					val notificationLabel = stringResource(R.string.ble_debug_display_notification, presetLabel)
					ElevatedButton(
						onClick = { onSend(notificationLabel, preset.type) },
						modifier = Modifier.weight(1f),
					) {
						Icon(Icons.Filled.Notifications, contentDescription = null)
						AppText(presetLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
				}
				if (row.size == 1) {
					Column(modifier = Modifier.weight(1f)) {}
				}
			}
		}
	}
}

@Composable
private fun NotificationExperimentCard(
	selectedClass: String,
	onClassSelected: (String) -> Unit,
) {
	DebugCard(title = stringResource(R.string.ble_debug_notification_experiments)) {
		AppText(
			text = stringResource(R.string.ble_debug_select_a_class_then_add_a_payload_in_custom_class_sender),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(
			horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
			modifier = Modifier.fillMaxWidth(),
		) {
			notificationClasses.take(4).forEach { classHex ->
				FilterChip(
					selected = selectedClass.equals(classHex, ignoreCase = true),
					onClick = { onClassSelected(classHex) },
					label = { AppText("0x${classHex.uppercase()}") },
				)
			}
		}
		Row(
			horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
			modifier = Modifier.fillMaxWidth(),
		) {
			notificationClasses.drop(4).forEach { classHex ->
				FilterChip(
					selected = selectedClass.equals(classHex, ignoreCase = true),
					onClick = { onClassSelected(classHex) },
					label = { AppText("0x${classHex.uppercase()}") },
				)
			}
		}
	}
}

@Composable
private fun CustomClassCard(
	state: BleDebugUiState,
	onClassChange: (String) -> Unit,
	onPayloadChange: (String) -> Unit,
	onSend: () -> Unit,
) {
	DebugCard(title = stringResource(R.string.ble_debug_custom_class_sender)) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
			modifier = Modifier.fillMaxWidth(),
		) {
			OutlinedTextField(
				value = state.customClassHex,
				onValueChange = onClassChange,
				label = { AppText(stringResource(R.string.ble_debug_class)) },
				keyboardOptions = hexKeyboardOptions(),
				singleLine = true,
				modifier = Modifier.widthIn(min = 96.dp, max = 112.dp),
			)
			OutlinedTextField(
				value = state.customPayloadHex,
				onValueChange = onPayloadChange,
				label = { AppText(stringResource(R.string.ble_debug_payload_hex)) },
				keyboardOptions = hexKeyboardOptions(),
				singleLine = true,
				modifier = Modifier.weight(1f),
			)
		}
		AppText(
			text = stringResource(R.string.ble_debug_preview, state.generatedCustomHex.ifBlank { stringResource(R.string.ble_debug_invalid) }),
			style = MaterialTheme.typography.bodyMedium,
			fontFamily = FontFamily.Monospace,
		)
		state.customValidationError?.let {
			AppText(it, color = MaterialTheme.colorScheme.error)
		}
		Button(
			onClick = onSend,
			modifier = Modifier.align(Alignment.End),
		) {
			Icon(Icons.Filled.Send, contentDescription = null)
			AppText(stringResource(R.string.ble_debug_send_custom))
		}
	}
}

@Composable
private fun RepeatCard(
	state: BleDebugUiState,
	onIntervalSelected: (Int) -> Unit,
	onStart: () -> Unit,
	onStop: () -> Unit,
) {
	DebugCard(title = stringResource(R.string.ble_debug_repeat_last_command)) {
		AppText(
			text = state.lastCommandLabel?.let { stringResource(R.string.ble_debug_last_command, it, state.lastCommandHex.orEmpty()) } ?: stringResource(R.string.ble_debug_no_command_sent_yet),
			style = MaterialTheme.typography.bodyMedium,
			fontFamily = if (state.lastCommandHex != null) FontFamily.Monospace else FontFamily.Default,
		)
		Row(
			horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.fillMaxWidth(),
		) {
			listOf(5, 30, 60).forEach { seconds ->
				FilterChip(
					selected = state.repeatIntervalSeconds == seconds,
					onClick = { onIntervalSelected(seconds) },
					label = { AppText(stringResource(R.string.ble_debug_seconds, seconds)) },
				)
			}
			if (state.repeating) {
				OutlinedButton(onClick = onStop) {
					Icon(Icons.Filled.Stop, contentDescription = null)
					AppText(stringResource(R.string.ble_debug_stop))
				}
			} else {
				Button(onClick = onStart) {
					Icon(Icons.Filled.PlayArrow, contentDescription = null)
					AppText(stringResource(R.string.ble_debug_start))
				}
			}
		}
	}
}

@Composable
private fun LogHeader(
	onCopy: () -> Unit,
	onClear: () -> Unit,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = Spacing.xs),
	) {
		AppText(
			text = stringResource(R.string.ble_debug_response_notify_log),
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.weight(1f),
		)
		TextButton(onClick = onCopy) {
			Icon(Icons.Filled.ContentCopy, contentDescription = null)
			AppText(stringResource(R.string.ble_debug_copy))
		}
		TextButton(onClick = onClear) {
			Icon(Icons.Filled.Delete, contentDescription = null)
			AppText(stringResource(R.string.ble_debug_clear))
		}
	}
}

@Composable
private fun LogRow(row: BleDebugLogRow) {
	AppCard(
		modifier = Modifier.fillMaxWidth(),
		padding = Spacing.xs,
		elevation = 0.dp,
	) {
		Column(
			verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
			modifier = Modifier.padding(Spacing.sm),
		) {
			Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
				AppText(
					text = row.timestamp,
					style = MaterialTheme.typography.labelSmall,
					fontFamily = FontFamily.Monospace,
				)
				AppText(
					text = row.label,
					style = MaterialTheme.typography.labelMedium,
					modifier = Modifier.weight(1f),
				)
			}
			if (row.hex.isNotBlank()) {
				AppText(
					text = row.hex,
					style = MaterialTheme.typography.bodySmall,
					fontFamily = FontFamily.Monospace,
					softWrap = true,
				)
			}
			AppText(
				text = row.result,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun DebugCard(
	title: String,
	content: @Composable ColumnScope.() -> Unit,
) {
	AppCard(modifier = Modifier.fillMaxWidth()) {
		Column(
			verticalArrangement = Arrangement.spacedBy(Spacing.sm),
			modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
		) {
			AppText(
				text = title,
				style = MaterialTheme.typography.titleMedium,
			)
			content()
		}
	}
}

@Composable
private fun StatusLine(
	label: String,
	value: String,
) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
		modifier = Modifier.fillMaxWidth(),
	) {
		AppText(
			text = label,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(1f),
		)
		AppText(
			text = value,
			fontFamily = FontFamily.Monospace,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1.25f),
		)
	}
}

private fun hexKeyboardOptions(): KeyboardOptions =
	KeyboardOptions(
		capitalization = KeyboardCapitalization.Characters,
		keyboardType = KeyboardType.Ascii,
	)
