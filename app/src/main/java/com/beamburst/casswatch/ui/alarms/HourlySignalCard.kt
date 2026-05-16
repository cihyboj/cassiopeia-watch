package com.beamburst.casswatch.ui.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppSwitch
import com.beamburst.casswatch.ui.common.AppText
import java.time.DayOfWeek
import kotlin.math.roundToInt

private val START_HOURS = mapOf(0 to 1, 1 to 7, 2 to 13, 3 to 19)
private val END_HOURS   = mapOf(1 to 6, 2 to 12, 3 to 18, 4 to 0)
private val TICK_LABELS = listOf("00", "06", "12", "18", "24")

private fun DayOfWeek.twoLetterLabel(): String = when (this) {
    DayOfWeek.MONDAY    -> "Mo"
    DayOfWeek.TUESDAY   -> "Tu"
    DayOfWeek.WEDNESDAY -> "We"
    DayOfWeek.THURSDAY  -> "Th"
    DayOfWeek.FRIDAY    -> "Fr"
    DayOfWeek.SATURDAY  -> "Sa"
    DayOfWeek.SUNDAY    -> "Su"
}

private fun startHourToPos(h: Int): Float =
    START_HOURS.entries.firstOrNull { it.value == h }?.key?.toFloat() ?: 1f

private fun endHourToPos(h: Int): Float =
    END_HOURS.entries.firstOrNull { it.value == h }?.key?.toFloat() ?: 3f

private fun endHourDisplay(h: Int): String = if (h == 0) "00" else "%02d".format(h)

@Composable
fun HourlySignalCard(
    modifier: Modifier = Modifier,
    alarmViewModel: AlarmViewModel = hiltViewModel(),
) {
    val state by alarmViewModel.hourlySignal.collectAsState()
    HourlySignalCardContent(
        state = state,
        onAction = alarmViewModel::onHourlySignalAction,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HourlySignalCardContent(
    state: HourlySignalUiState,
    onAction: (HourlySignalAction) -> Unit,
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

    val controlsAlpha = if (state.enabled) 1f else 0.38f

    AppCard(modifier = modifier.fillMaxWidth()) {
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
                        TICK_LABELS.forEach { label ->
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
                            "%02d".format(currentStartHour),
                            endHourDisplay(currentEndHour),
                            count,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppText(
                        text = stringResource(R.string.hourly_signal_requires_auto_sync),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                selected = day in state.days,
                                onClick = {
                                    val newDays = if (day in state.days) state.days - day else state.days + day
                                    onAction(HourlySignalAction.SetDays(newDays))
                                },
                                label = { Text(day.twoLetterLabel()) },
                            )
                        }
                    }

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

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        listOf(
                            stringResource(R.string.hourly_signal_preset_weekdays) to weekdays,
                            stringResource(R.string.hourly_signal_preset_weekend)  to weekend,
                            stringResource(R.string.hourly_signal_preset_every_day) to allWeek,
                        ).forEach { (label, days) ->
                            FilterChip(
                                selected = state.days == days,
                                onClick = { onAction(HourlySignalAction.SetDays(days)) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }
    }
}
