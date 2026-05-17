package com.beamburst.casswatch.ui.alarms

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.CassiopeiaWatchTheme
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppButton
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.ItemView
import com.beamburst.casswatch.ui.common.ScreenTitle
import com.beamburst.casswatch.ui.common.SendToWatchActionBar
import com.beamburst.casswatch.ui.time.TimerCardContent
import com.beamburst.casswatch.ui.time.TimerViewModel
import java.time.DayOfWeek
import android.content.res.Configuration

private enum class AlarmHubTab { ALARMS, TIMER, SIGNAL }

@Composable
fun AlarmList(alarmViewModel: AlarmViewModel = hiltViewModel()) {
    val alarms by alarmViewModel.alarms.collectAsState()
    val alarmDays by alarmViewModel.alarmDays.collectAsState()
    val firedAts by alarmViewModel.firedAts.collectAsState()
    val syncWarnings by alarmViewModel.syncWarnings.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        alarms.forEachIndexed { index, alarm ->
            key(index) {
                ItemView {
                    AlarmItem(
                        hours = alarm.hour,
                        minutes = alarm.minute,
                        isAlarmEnabled = alarm.enabled,
                        showAsEnabled = alarm.enabled && !firedAts.containsKey(index),
                        name = alarm.name,
                        onToggleAlarm = { isEnabled ->
                            alarmViewModel.toggleAlarm(index, isEnabled)
                        },
                        onTap = { alarmViewModel.openEditor(index) },
                        showDaySelector = true,
                        selectedDays = alarmDays[index] ?: AlarmSchedulePlanner.AllDays,
                        syncWarning = syncWarnings[index],
                    )
                }
            }
        }
    }
}

@Composable
private fun ExactAlarmPermissionDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.exact_alarm_dialog_title)) },
        text = { Text(stringResource(R.string.exact_alarm_dialog_body)) },
        confirmButton = {
            AppButton(
                text = stringResource(R.string.exact_alarm_dialog_settings),
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    onOpenSettings()
                }
            )
        },
        dismissButton = {
            AppButton(
                text = stringResource(R.string.exact_alarm_dialog_not_now),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun AlarmHubActionRow(
    activeTab: AlarmHubTab,
    alarmViewModel: AlarmViewModel,
    timerViewModel: TimerViewModel,
    modifier: Modifier = Modifier,
) {
    val isConnected by alarmViewModel.isConnected.collectAsState()

    if (!isConnected) return

    SendToWatchActionBar(
        onClick = {
            when (activeTab) {
                AlarmHubTab.ALARMS -> alarmViewModel.sendAlarmsToWatch()
                AlarmHubTab.TIMER -> timerViewModel.sendTimerToWatch()
                AlarmHubTab.SIGNAL -> alarmViewModel.sendHourlySignalToWatch()
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmHubTabs(
    activeTab: AlarmHubTab,
    alarmSubtitle: String,
    timerSubtitle: String,
    signalSubtitle: String,
    onTabSelected: (AlarmHubTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        AlarmHubTab.ALARMS to (stringResource(R.string.alarms) to alarmSubtitle),
        AlarmHubTab.TIMER to (stringResource(R.string.timer) to timerSubtitle),
        AlarmHubTab.SIGNAL to (stringResource(R.string.alarm_hub_signal) to signalSubtitle),
    )
    val count = tabs.size
    val outerShape = RoundedCornerShape(16.dp)
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    val selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val unselectedContentColor = MaterialTheme.colorScheme.onSurface
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)

    Surface(
        modifier = modifier,
        shape = outerShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, outlineColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(outerShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, (tab, labels) ->
                val selected = tab == activeTab
                val segmentShape = when (index) {
                    0 -> RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                    count - 1 -> RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(segmentShape)
                        .background(if (selected) selectedColor else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = labels.first,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) selectedContentColor else unselectedContentColor,
                        )
                        Text(
                            text = labels.second,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (selected) {
                                    selectedContentColor.copy(alpha = 0.75f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
                if (index < count - 1) {
                    VerticalDivider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp),
                        thickness = 1.dp,
                        color = outlineColor,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsScreen(
    alarmViewModel: AlarmViewModel = hiltViewModel(),
    timerViewModel: TimerViewModel = hiltViewModel(),
    onNavigateToHistory: () -> Unit = {},
    onOpenSyncSettings: () -> Unit = {},
) {
    val alarms by alarmViewModel.alarms.collectAsState()
    val hourlySignal by alarmViewModel.hourlySignal.collectAsState()
    val timerState by timerViewModel.state.collectAsState()
    val editorTarget by alarmViewModel.editorTarget.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showPermissionDialog by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(AlarmHubTab.ALARMS) }

    LaunchedEffect(Unit) {
        alarmViewModel.uiEvents.collect { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> {
                    AppSnackbar(event.message)
                }
                is UiEvent.RequestExactAlarmPermission -> showPermissionDialog = true
            }
        }
    }

    if (showPermissionDialog) {
        ExactAlarmPermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onOpenSettings = { showPermissionDialog = false }
        )
    }

    editorTarget?.let { draft ->
        AlarmEditorSheet(
            draft = draft,
            sheetState = sheetState,
            onSave = { hour, minute, name, days ->
                alarmViewModel.upsertAlarm(draft.index, hour, minute, name, days)
            },
            onDismiss = { alarmViewModel.dismissEditor() }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .padding(bottom = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                ScreenTitle(
                    text = stringResource(id = R.string.watch_alarms),
                    modifier = Modifier
                )

                AlarmsFooter(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                    alarmViewModel = alarmViewModel,
                    onLastSyncClick = onNavigateToHistory,
                )

                AlarmHubTabs(
                    activeTab = activeTab,
                    alarmSubtitle = alarmTabSubtitle(alarms),
                    timerSubtitle = timerTabSubtitle(timerState.timer),
                    signalSubtitle = signalTabSubtitle(hourlySignal),
                    onTabSelected = { activeTab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg)
                )

                when (activeTab) {
                    AlarmHubTab.ALARMS -> AlarmList(alarmViewModel)
                    AlarmHubTab.TIMER -> TimerCardContent(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg),
                        timerSeconds = timerState.timer,
                        onSetTimer = { minutes, seconds ->
                            timerViewModel.setTimer(0, minutes, seconds)
                        },
                    )
                    AlarmHubTab.SIGNAL -> HourlySignalCard(
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        onOpenSyncSettings = onOpenSyncSettings,
                    )
                }
            }

            AlarmHubActionRow(
                activeTab = activeTab,
                alarmViewModel = alarmViewModel,
                timerViewModel = timerViewModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }
    }
}

@Composable
private fun AlarmsScreenPreviewContent() {
    CassiopeiaWatchTheme(dynamicColor = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                        .padding(bottom = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    ScreenTitle(
                        text = stringResource(R.string.watch_alarms),
                        modifier = Modifier,
                        showStatusBadge = false,
                    )

                    AlarmHubTabs(
                        activeTab = AlarmHubTab.ALARMS,
                        alarmSubtitle = "1 ON",
                        timerSubtitle = "05:30",
                        signalSubtitle = "07-18",
                        onTabSelected = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                    )

                    Column(modifier = Modifier.fillMaxWidth()) {
                        ItemView {
                            AlarmItem(
                                hours = 11,
                                minutes = 15,
                                name = stringResource(R.string.alarm_preview_name_morning),
                                isAlarmEnabled = true,
                                onToggleAlarm = {},
                                onTap = {},
                                showDaySelector = true,
                                selectedDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
                            )
                        }
                        ItemView {
                            AlarmItem(
                                hours = 13,
                                minutes = 15,
                                name = "Lunch",
                                isAlarmEnabled = false,
                                onToggleAlarm = {},
                                onTap = {},
                                showDaySelector = true,
                                selectedDays = AlarmSchedulePlanner.AllDays
                            )
                        }
                    }

                    PreviewHourlySignalCard(
                        enabled = true,
                        caption = "7-18 active window",
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewHourlySignalCard(
    enabled: Boolean,
    caption: String,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            AppText(
                text = "Hourly signal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            AppText(
                text = if (enabled) caption else "Off",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
            )
        }
    }
}

@Preview(name = "Alarms simple", showBackground = true)
@Composable
private fun PreviewAlarmsScreenSimple() {
    AlarmsScreenPreviewContent()
}

@Preview(
    name = "Alarms weekly",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewAlarmsScreenWeekly() {
    AlarmsScreenPreviewContent()
}
