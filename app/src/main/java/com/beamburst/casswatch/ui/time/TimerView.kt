package com.beamburst.casswatch.ui.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppButton
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.AppWheelPicker

private data class Preset(val labelRes: Int, val totalSeconds: Int)
private val PRESETS = listOf(
    Preset(R.string.timer_preset_5min,  300),
    Preset(R.string.timer_preset_10min, 600),
    Preset(R.string.timer_preset_15min, 900),
    Preset(R.string.timer_preset_20min, 1200),
    Preset(R.string.timer_preset_25min, 1500),
    Preset(R.string.timer_preset_30min, 1800),
    Preset(R.string.timer_preset_45min, 2700),
    Preset(R.string.timer_preset_60min, 3599),
)

@Composable
fun TimerView(
    modifier: Modifier = Modifier,
    timerViewModel: TimerViewModel = hiltViewModel(),
) {
    val state by timerViewModel.state.collectAsState()
    TimerCardContent(
        modifier = modifier,
        timerSeconds = state.timer,
        onSetTimer = { minutes, seconds -> timerViewModel.setTimer(0, minutes, seconds) },
    )
}

@Composable
fun TimerCardContent(
    timerSeconds: Int,
    onSetTimer: (minutes: Int, seconds: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minutes = (timerSeconds % 3600) / 60
    val seconds = timerSeconds % 60

    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            // Header: icon + title + reset button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiary,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    AppText(
                        text = stringResource(R.string.timer),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                TextButton(onClick = { onSetTimer(0, 0) }) {
                    Icon(
                        imageVector = Icons.Rounded.Replay,
                        contentDescription = stringResource(R.string.timer_reset),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.timer_reset),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimerDrumPicker(
                        value = minutes,
                        onValueChange = { m -> onSetTimer(m, seconds) },
                        modifier = Modifier.width(80.dp),
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(horizontal = Spacing.sm),
                    )
                    TimerDrumPicker(
                        value = seconds,
                        onValueChange = { s -> onSetTimer(minutes, s) },
                        modifier = Modifier.width(80.dp),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.timer_label_min),
                        modifier = Modifier.width(80.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.sm * 2))
                    Text(
                        text = stringResource(R.string.timer_label_sec),
                        modifier = Modifier.width(80.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            // Preset chips
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PRESETS.chunked(4).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        rowPresets.forEach { preset ->
                            FilterChip(
                                modifier = Modifier.weight(1f),
                                selected = false,
                                onClick = {
                                    val m = preset.totalSeconds / 60
                                    val s = preset.totalSeconds % 60
                                    onSetTimer(m, s)
                                },
                                label = {
                                    Text(
                                        text = stringResource(preset.labelRes),
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center,
                                    )
                                },
                            )
                        }
                        repeat(4 - rowPresets.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerDrumPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayedValues = remember { (0..59).map { "%02d".format(it) } }
    AppWheelPicker(
        values = displayedValues,
        selectedIndex = value,
        onSelectedIndexChange = onValueChange,
        modifier = modifier,
    )
}

@Composable
fun SendTimerButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    AppButton(
        text = stringResource(R.string.send_to_watch),
        onClick = onClick,
        modifier = modifier,
    )
}

fun convertSecondsToTime(totalSeconds: Int): Triple<Int, Int, Int> {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return Triple(hours, minutes, seconds)
}
