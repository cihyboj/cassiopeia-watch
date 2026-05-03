package com.beamburst.casswatch.ui.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import AppSwitch
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppText

@Composable
fun HourlySignalCard(
    modifier: Modifier = Modifier,
    alarmViewModel: AlarmViewModel = hiltViewModel(),
) {
    val state by alarmViewModel.hourlySignal.collectAsState()

    val endDisplay = if (state.endHour == 0) "00" else String.format("%02d", state.endHour)
    val startDisplay = String.format("%d", state.startHour)
    val caption = stringResource(R.string.hourly_signal_window_caption, startDisplay, endDisplay)

    if (state.showDialog) {
        HourlySignalDialog(
            currentStartHour = state.startHour,
            currentEndHour = state.endHour,
            onSave = { start, end ->
                alarmViewModel.onHourlySignalAction(HourlySignalAction.SaveWindow(start, end))
            },
            onDismiss = {
                alarmViewModel.onHourlySignalAction(HourlySignalAction.DismissDialog)
            }
        )
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    alarmViewModel.onHourlySignalAction(HourlySignalAction.OpenDialog)
                }
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = stringResource(R.string.hourly_signal_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AppText(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (state.enabled) 1f else 0.5f
                    )
                )
            }
            AppSwitch(
                checked = state.enabled,
                onCheckedChange = { on ->
                    alarmViewModel.onHourlySignalAction(HourlySignalAction.SetEnabled(on))
                }
            )
        }
    }
}
