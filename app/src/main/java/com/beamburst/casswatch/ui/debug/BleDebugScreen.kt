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
	val label: String,
	val hex: String,
)

private val alarmPresets = listOf(
	DebugPreset("Alarm 1 OFF 07:00", "1500400700"),
	DebugPreset("Alarm 1 ON 07:00", "1540400700"),
	DebugPreset("Alarm 1 ON 08:00", "1540400800"),
	DebugPreset("Alarm 1 ON 09:00", "1540400900"),
	DebugPreset("Alarm 1 one-time 07:00", "1560400700"),
)

private val buzzerPresets = listOf(
	DebugPreset("Buzzer start alarm", "4c0001"),
	DebugPreset("Buzzer stop alarm", "4c0201"),
	DebugPreset("Buzzer start timer", "4c0000"),
	DebugPreset("Buzzer stop timer", "4c0200"),
	DebugPreset("Buzzer start auto-lap", "4c0002"),
	DebugPreset("Buzzer stop auto-lap", "4c0202"),
)

private val phoneFinderPresets = listOf(
	DebugPreset("No alert", "0100"),
	DebugPreset("Mild alert", "0101"),
	DebugPreset("High alert", "0102"),
)

private val bleTimeoutPresets = listOf(
	DebugPreset("Timeout 3 min", "110F0F0F0600500004000100001E03"),
	DebugPreset("Timeout 5 min", "110F0F0F0600500004000100001E05"),
	DebugPreset("Timeout 10 min", "110F0F0F0600500004000100001E0A"),
	DebugPreset("Timeout 60 min", "110F0F0F0600500004000100001E3C"),
	DebugPreset("Timeout 90 min", "110F0F0F0600500004000100001E5A"),
	DebugPreset("Timeout 120 min", "110F0F0F0600500004000100001E78"),
)

private val legacyAlertNotificationPresets = listOf(
	DebugPreset("New alert email", "070101454D41494C2054455354"),
	DebugPreset("New alert call", "07030143414C4C2054455354"),
	DebugPreset("New alert SMS", "070501534D532054455354"),
	DebugPreset("New alert schedule", "07070143414C2054455354"),
	DebugPreset("New alert SNS", "070D01534E532054455354"),
	DebugPreset("More alert TEST", "0E0000002E202054455354"),
	DebugPreset("More alert ABCDEF", "0E0000002E414243444546"),
	DebugPreset("More alert clear", "0E00000000"),
	DebugPreset("Long alert music", "0F000A0154455354204D55534943"),
	DebugPreset("Long alert SNS", "0F000D015445535420534E53"),
	DebugPreset("Long alert email", "0F0001015445535420454D41494C"),
	DebugPreset("Long alert schedule", "0F000701544553542043414C"),
)

private val stepReadPresets = listOf(
	DebugPreset("Read step class", "4d"),
	DebugPreset("Read step unit 0", "4d00"),
	DebugPreset("Read step unit 1", "4d01"),
	DebugPreset("Read watch condition", "28"),
)

private val notificationReadPresets = listOf(
	DebugPreset("Read BLE settings", "11"),
	DebugPreset("Read notification manager", "3d30"),
	DebugPreset("Read custom alert", "3f"),
	DebugPreset("Read notification method", "42"),
)

private val featureDiscoveryReadPresets = listOf(
	DebugPreset("Read timer", "18"),
	DebugPreset("Read stopwatch", "1c"),
	DebugPreset("Read version info", "20"),
	DebugPreset("Read watch condition", "28"),
	DebugPreset("Read module ID", "26"),
	DebugPreset("Read app info", "22"),
	DebugPreset("Read watch name", "23"),
	DebugPreset("Read location/radio", "24"),
)

private val notificationClasses = listOf("07", "0c", "0d", "0e", "0f", "3d", "3f", "42")

private data class NotificationPreset(
	val label: String,
	val type: NotificationType,
)

private val displayNotificationPresets = listOf(
	NotificationPreset("Generic", NotificationType.GENERIC),
	NotificationPreset("Message", NotificationType.MESSAGE),
	NotificationPreset("Email", NotificationType.EMAIL),
	NotificationPreset("Calendar", NotificationType.CALENDAR),
	NotificationPreset("Phone call", NotificationType.PHONE_CALL),
	NotificationPreset("Urgent call", NotificationType.PHONE_CALL_URGENT),
	NotificationPreset("Email/SMS", NotificationType.EMAIL_SMS),
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
			text = "BLE Debug",
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
				PresetSection("Alarm", alarmPresets, viewModel::sendPreset)
			}
			item {
				PresetSection("Buzzer", buzzerPresets, viewModel::sendPreset)
			}
			item {
				PresetSection("Phone finder / immediate alert", phoneFinderPresets, viewModel::sendPreset)
			}
			item {
				PresetSection("BLE connection timeout", bleTimeoutPresets, viewModel::sendPreset)
			}
			item {
				ReadProbeSection("Step counter read probes", stepReadPresets, viewModel::sendReadRequest)
			}
			item {
				ReadProbeSection("Notification config read probes", notificationReadPresets, viewModel::sendReadRequest)
			}
			item {
				ReadProbeSection("Feature discovery read probes", featureDiscoveryReadPresets, viewModel::sendReadRequest)
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
				PresetSection("Casio alert notification probes", legacyAlertNotificationPresets, viewModel::sendPreset)
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
	DebugCard(title = "Connection status") {
		StatusLine("Connected", if (state.connected) "yes" else "no")
		StatusLine("Device", state.deviceName.ifBlank { "Unknown" })
		StatusLine("Address", state.deviceAddress.ifBlank { "Unknown" })
		StatusLine("Service 26eb000d", if (state.serviceFound) "yes" else "no")
		StatusLine("Command 26eb002d", if (state.commandCharacteristicFound) "yes" else "no")
		StatusLine("Read request 26eb002c", if (state.readCharacteristicFound) "yes" else "no")
		StatusLine("Notification writer", if (state.notificationCharacteristicFound) "yes" else "no")
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
	DebugCard(title = "Manual command sender") {
		OutlinedTextField(
			value = state.manualLabel,
			onValueChange = onLabelChange,
			label = { AppText("Optional label") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = state.manualHex,
			onValueChange = onHexChange,
			label = { AppText("HEX input") },
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
			AppText("Dry run", modifier = Modifier.weight(1f))
			OutlinedButton(onClick = onClear) {
				Icon(Icons.Filled.Delete, contentDescription = null)
				AppText("Clear log")
			}
			Button(onClick = onSend) {
				Icon(Icons.Filled.Send, contentDescription = null)
				AppText("Send")
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
					ElevatedButton(
						onClick = { onSend(preset.label, preset.hex) },
						modifier = Modifier.weight(1f),
					) {
						Column(horizontalAlignment = Alignment.Start) {
							AppText(
								text = preset.label,
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
			text = "Sends raw read requests to 26eb002c. Watch responses or errors should appear in the notify log.",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		presets.chunked(2).forEach { row ->
			Row(
				horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
				modifier = Modifier.fillMaxWidth(),
			) {
				row.forEach { preset ->
					ElevatedButton(
						onClick = { onRead(preset.label, preset.hex) },
						modifier = Modifier.weight(1f),
					) {
						Column(horizontalAlignment = Alignment.Start) {
							AppText(
								text = preset.label,
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
	DebugCard(title = "Display / app notification") {
		AppText(
			text = "Uses the app notification encoder and notification characteristic, not a guessed raw payload.",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		OutlinedTextField(
			value = state.notificationApp,
			onValueChange = onAppChange,
			label = { AppText("App label") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = state.notificationTitle,
			onValueChange = onTitleChange,
			label = { AppText("Title") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = state.notificationText,
			onValueChange = onTextChange,
			label = { AppText("Text") },
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
					ElevatedButton(
						onClick = { onSend("Display notification: ${preset.label}", preset.type) },
						modifier = Modifier.weight(1f),
					) {
						Icon(Icons.Filled.Notifications, contentDescription = null)
						AppText(preset.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
	DebugCard(title = "Notification experiments") {
		AppText(
			text = "Select a class, then add a payload in Custom class sender.",
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
	DebugCard(title = "Custom class sender") {
		Row(
			horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
			modifier = Modifier.fillMaxWidth(),
		) {
			OutlinedTextField(
				value = state.customClassHex,
				onValueChange = onClassChange,
				label = { AppText("Class") },
				keyboardOptions = hexKeyboardOptions(),
				singleLine = true,
				modifier = Modifier.widthIn(min = 96.dp, max = 112.dp),
			)
			OutlinedTextField(
				value = state.customPayloadHex,
				onValueChange = onPayloadChange,
				label = { AppText("Payload HEX") },
				keyboardOptions = hexKeyboardOptions(),
				singleLine = true,
				modifier = Modifier.weight(1f),
			)
		}
		AppText(
			text = "Preview: ${state.generatedCustomHex.ifBlank { "(invalid)" }}",
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
			AppText("Send custom")
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
	DebugCard(title = "Repeat last command") {
		AppText(
			text = state.lastCommandLabel?.let { "$it (${state.lastCommandHex})" } ?: "No command sent yet.",
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
					label = { AppText("${seconds}s") },
				)
			}
			if (state.repeating) {
				OutlinedButton(onClick = onStop) {
					Icon(Icons.Filled.Stop, contentDescription = null)
					AppText("Stop")
				}
			} else {
				Button(onClick = onStart) {
					Icon(Icons.Filled.PlayArrow, contentDescription = null)
					AppText("Start")
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
			text = "Response / notify log",
			style = MaterialTheme.typography.titleMedium,
			modifier = Modifier.weight(1f),
		)
		TextButton(onClick = onCopy) {
			Icon(Icons.Filled.ContentCopy, contentDescription = null)
			AppText("Copy")
		}
		TextButton(onClick = onClear) {
			Icon(Icons.Filled.Delete, contentDescription = null)
			AppText("Clear")
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
