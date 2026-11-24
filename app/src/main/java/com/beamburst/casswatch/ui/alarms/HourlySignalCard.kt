package com.beamburst.casswatch.ui.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.AutoSyncSchedule
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppDayPicker
import com.beamburst.casswatch.ui.common.AppSwitch
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.AppTogglePill
import java.time.DayOfWeek
import kotlin.math.roundToInt

private val START_HOURS = mapOf(0 to 1, 1 to 7, 2 to 13, 3 to 19)
private val END_HOURS   = mapOf(1 to 6, 2 to 12, 3 to 18, 4 to 0)

private fun startHourToPos(h: Int): Float =
    START_HOURS.entries.firstOrNull { it.value == h }?.key?.toFloat() ?: 1f

private fun endHourToPos(h: Int): Float =
    END_HOURS.entries.firstOrNull { it.value == h }?.key?.toFloat() ?: 3f

private fun hourDisplay(h: Int): String = if (h == 24) "00" else "%02d".format(h)

@Composable
fun HourlySignalCard(
    modifier: Modifier = Modifier,
    onOpenSyncSettings: () -> Unit = {},
    alarmViewModel: AlarmViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        alarmViewModel.refreshAutomaticSyncEnabled()
    }
    val state by alarmViewModel.hourlySignal.collectAsState()
    val automaticSyncEnabled by alarmViewModel.automaticSyncEnabled.collectAsState()
    HourlySignalCardContent(
        state = state,
        automaticSyncEnabled = automaticSyncEnabled,
        onAction = alarmViewModel::onHourlySignalAction,
        onOpenSyncSettings = onOpenSyncSettings,
        modifier = modifier,
    )
}

@Composable
fun HourlySignalCardContent(
    state: HourlySignalUiState,
    automaticSyncEnabled: Boolean,
    onAction: (HourlySignalAction) -> Unit,
    onOpenSyncSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderPos by remember(state.startHour, state.endHour) {
        mutableStateOf(startHourToPos(state.startHour)..endHourToPos(state.endHour))
    }

    val startPos = sliderPos.start.roundToInt()
    val endPos   = sliderPos.endInclusive.roundToInt()
    val currentStartHour = START_HOURS[startPos] ?: state.startHour
    val currentEndHour   = END_HOURS[endPos] ?: state.endHour
    val count = chimeCount(currentStartHour, currentEndHour)
    // Stored in the hour-0 cycle; shown as the hours the watch really chimes.
    val (shownStart, shownEnd) = shownWindow(currentStartHour, currentEndHour, state.syncShift)
    val tickLabels = signalSegmentEdges(state.syncShift, state.syncMinute)

    val controlsAlpha = if (state.enabled) 1f else 0.38f

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    AppText(
                        text = stringResource(R.string.hourly_signal_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                AppSwitch(
                    checked = state.enabled,
                    onCheckedChange = { on ->
                        if (on && state.days.isEmpty()) {
                            onAction(HourlySignalAction.SetDays(DayOfWeek.entries.toSet()))
                        }
                        onAction(HourlySignalAction.SetEnabled(on))
                    },
                )
            }

            Spacer(Modifier.height(Spacing.md))

            // Range slider
            Box(modifier = Modifier.alpha(controlsAlpha)) {
                Column {
                    RangeSlider(
                        value = sliderPos,
                        onValueChange = { new ->
                            if (new.start.roundToInt() < new.endInclusive.roundToInt()) {
                                sliderPos = new
                            }
                        },
                        onValueChangeFinished = {
                            val newStart = START_HOURS[startPos] ?: return@RangeSlider
                            val newEnd   = END_HOURS[endPos]     ?: return@RangeSlider
                            onAction(HourlySignalAction.SetWindow(newStart, newEnd))
                        },
                        valueRange = 0f..4f,
                        steps = 3,
                        enabled = state.enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        tickLabels.forEach { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            // Chime info
            Box(modifier = Modifier.alpha(controlsAlpha)) {
                Column {
                    AppText(
                        text = stringResource(
                            R.string.hourly_signal_chimes_from,
                            hourDisplay(shownStart),
                            hourDisplay(shownEnd),
                            count,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider()
            Spacer(Modifier.height(Spacing.sm))

            // Active days
            Box(modifier = Modifier.alpha(controlsAlpha)) {
                Column {
                    AppText(
                        text = stringResource(R.string.hourly_signal_active_days),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    AppDayPicker(
                        selectedDays = state.days,
                        onDayClick = { day ->
                            val newDays = if (day in state.days) state.days - day else state.days + day
                            onAction(HourlySignalAction.SetDays(newDays))
                        },
                    )

                    if (state.days.isEmpty()) {
                        Spacer(Modifier.height(Spacing.xs))
                        AppText(
                            text = stringResource(R.string.hourly_signal_no_days_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(Spacing.sm))

                    // Day presets
                    val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                    val weekend  = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                    val allWeek  = DayOfWeek.entries.toSet()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        listOf(
                            stringResource(R.string.hourly_signal_preset_weekdays) to weekdays,
                            stringResource(R.string.hourly_signal_preset_weekend)  to weekend,
                            stringResource(R.string.hourly_signal_preset_every_day) to allWeek,
                        ).forEach { (label, days) ->
                            HourlySignalPresetPill(
                                modifier = Modifier.weight(1f),
                                label = label,
                                selected = state.days == days,
                                onClick = { onAction(HourlySignalAction.SetDays(days)) },
                            )
                        }
                    }
                }
            }
        }
        }
        if (showHourlySignalAutoSyncHint(state)) {
            HourlySignalSyncInfoCard(
                syncTimes = AutoSyncSchedule.format(state.syncShift, state.syncMinute),
                automaticSyncEnabled = automaticSyncEnabled,
                onOpenSyncSettings = onOpenSyncSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HourlySignalPresetPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppTogglePill(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun HourlySignalSyncInfoCard(
    syncTimes: String,
    automaticSyncEnabled: Boolean,
    onOpenSyncSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetails by remember { mutableStateOf(false) }
    val alert = !automaticSyncEnabled
    val container = if (alert) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.82f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }
    val content = if (alert) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    if (showDetails) {
        HourlySignalSyncDetailsDialog(
            syncTimes = syncTimes,
            onDismiss = { showDetails = false },
            onOpenSyncSettings = {
                showDetails = false
                onOpenSyncSettings()
            },
        )
    }

    AppCard(
        modifier = modifier,
        containerColor = container,
        borderColor = content.copy(alpha = 0.16f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDetails = true }
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = stringResource(
                        if (alert) R.string.hourly_signal_auto_sync_alert_title
                        else R.string.hourly_signal_auto_sync_info_title
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
                AppText(
                    text = stringResource(R.string.hourly_signal_auto_sync_info_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.88f),
                )
            }
            IconButton(onClick = { showDetails = true }) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.info),
                    tint = content,
                )
            }
        }
    }
}

@Composable
private fun HourlySignalSyncDetailsDialog(
    syncTimes: String,
    onDismiss: () -> Unit,
    onOpenSyncSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hourly_signal_auto_sync_dialog_title)) },
        text = {
            Text(
                text = stringResource(R.string.hourly_signal_auto_sync_dialog_body, syncTimes),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSyncSettings) {
                Text(stringResource(R.string.open_sync_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}
