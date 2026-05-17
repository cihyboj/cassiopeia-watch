package com.beamburst.casswatch.ui.settings

import com.beamburst.casswatch.ui.common.AppSwitch
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.AppTextLink
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.ui.common.InfoButton
import com.beamburst.casswatch.ui.common.ValueSelectionDialog
import org.avmedia.gshockapi.WatchInfo
import androidx.compose.runtime.mutableIntStateOf
import com.beamburst.casswatch.theme.Spacing
import androidx.compose.foundation.clickable

@Composable
fun TimeAdjustment(
    onUpdate: (SettingsViewModel.TimeAdjustment) -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val classType = SettingsViewModel.TimeAdjustment::class.java
    val state by settingsViewModel.state.collectAsState()
    val timeAdjustmentSetting: SettingsViewModel.TimeAdjustment =
        settingsViewModel.getSetting(classType)

    var autoAdjust by remember { mutableStateOf(timeAdjustmentSetting.timeAdjustment) }
    var notifyMe by remember { mutableStateOf(timeAdjustmentSetting.timeAdjustmentNotifications) }
    var adjustmentMinutes by remember { mutableStateOf(timeAdjustmentSetting.adjustmentTimeMinutes.toString()) }
    var fineAdjustment by remember { mutableIntStateOf(timeAdjustmentSetting.fineAdjustment) }

    LaunchedEffect(state) {
        autoAdjust = timeAdjustmentSetting.timeAdjustment
        notifyMe = timeAdjustmentSetting.timeAdjustmentNotifications
        adjustmentMinutes = timeAdjustmentSetting.adjustmentTimeMinutes.toString()
        fineAdjustment = timeAdjustmentSetting.fineAdjustment
    }

    SettingCard(modifier = Modifier.fillMaxWidth()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
        ) {
            AutoAdjustmentRow(
                checked = autoAdjust,
                onCheckedChange = { newValue ->
                    autoAdjust = newValue
                    onUpdate(timeAdjustmentSetting.copy(timeAdjustment = newValue))
                },
            )
            if (WatchInfo.alwaysConnected) {
                FineAdjustmentRow(
                    modifier = Modifier
                        .padding(top = Spacing.xs),
                    value = fineAdjustment,
                    onValueChange = { newValue ->
                        fineAdjustment = newValue
                        onUpdate(timeAdjustmentSetting.copy(fineAdjustment = newValue))
                    }
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SettingsLabel(text = stringResource(id = R.string.when_to_run))
                        AppText(
                            text = stringResource(id = R.string.minutes_after_hour),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoButton(
                        infoText = stringResource(
                            id = R.string.adjustment_time_info
                        )
                    )

                    var showDialog by remember { mutableStateOf(false) }

                    AppTextLink(
                        text = "$adjustmentMinutes m",
                        modifier = Modifier
                            .clickable { showDialog = true }
                            .padding(Spacing.sm),
                    )
                    if (showDialog) {
                        ValueSelectionDialog(
                            initialValue = adjustmentMinutes.toInt(),
                            range = 0..59,
                            onDismiss = { showDialog = false },
                            onConfirm = { newValue ->
                                showDialog = false
                                onUpdate(
                                    timeAdjustmentSetting.copy(
                                        adjustmentTimeMinutes = newValue
                                    )
                                )
                            },
                            title = stringResource(
                                R.string.when_to_run
                            ),
                            label = stringResource(
                                R.string.minutes_between_0_and_59
                            ),
                            unit = "m"
                        )
                    }
                }
                FineAdjustmentRow(
                    modifier = Modifier
                        .padding(top = Spacing.xs),
                    value = fineAdjustment,
                    onValueChange = { newValue ->
                        fineAdjustment = newValue
                        onUpdate(timeAdjustmentSetting.copy(fineAdjustment = newValue))
                    }
                )
            }
            NotifyMeRow(
                checked = notifyMe,
                onCheckedChange = { newValue ->
                    notifyMe = newValue
                    timeAdjustmentSetting.timeAdjustmentNotifications = newValue
                    onUpdate(timeAdjustmentSetting.copy(timeAdjustmentNotifications = newValue))
                }
            )
        }
    }
}

@Composable
private fun AutoAdjustmentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SettingsLabel(text = stringResource(id = R.string.auto_time_adjustment_master))
            AppText(
                text = stringResource(id = R.string.auto_time_adjustment_master_caption),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NotifyMeRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsLabel(
            text = stringResource(id = R.string.notify_on_auto_sync),
            modifier = Modifier.weight(1f)
        )
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewTimeAdjustment() {
    TimeAdjustment(onUpdate = {})
}
