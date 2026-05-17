package com.beamburst.casswatch.ui.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.BroadcastSpec
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.CassiopeiaWatchTheme
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.ScreenTitle
import com.beamburst.casswatch.ui.common.SendToWatchActionBar
import com.beamburst.casswatch.utils.NtpTimeSettings
import kotlinx.coroutines.delay
import org.avmedia.gshockapi.WatchInfo

enum class SettingsAnchor(val routeValue: String) {
    Sync("sync"),
}

@SuppressLint("MutableCollectionMutableState")
@Composable
fun SettingsScreen(
    onNavigateToHistory: () -> Unit = {},
    anchor: String? = null,
) {
    val scrollState = rememberScrollState()
    var syncOffset by remember { mutableIntStateOf(0) }
    var highlightSync by remember(anchor) { mutableStateOf(anchor == SettingsAnchor.Sync.routeValue) }

    LaunchedEffect(anchor, syncOffset) {
        if (anchor == SettingsAnchor.Sync.routeValue && syncOffset > 0) {
            scrollState.animateScrollTo(syncOffset)
            highlightSync = true
            delay(1800)
            highlightSync = false
        }
    }

    CassiopeiaWatchTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenTitle(
                    stringResource(id = R.string.settings),
                    Modifier,
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = Spacing.lg)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    SettingsList(
                        onNavigateToHistory = onNavigateToHistory,
                        highlightSync = highlightSync,
                        onSyncSectionPositioned = { syncOffset = it },
                    )
                }

                BottomRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            }
        }
    }
}

@Composable
fun SettingsList(
    onNavigateToHistory: () -> Unit = {},
    highlightSync: Boolean = false,
    onSyncSectionPositioned: (Int) -> Unit = {},
) {
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    LaunchedEffect(Unit) {
        settingsViewModel.uiEvents.collect { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> {
                    AppSnackbar(event.message)
                }
            }
        }
    }

    // ── Actions replacement: top-of-Settings rows ─────────────────────────
    val pressCards by settingsViewModel.pressCards.collectAsState()

    SettingsSectionTitle(stringResource(R.string.settings_section_actions))

    PressCard(
        title = stringResource(R.string.short_button_press),
        subtitle = BroadcastSpec.Short.action,
        enabled = pressCards.shortBroadcast.enabled,
        onEnabledChange = { on ->
            settingsViewModel.onPressCardAction(PressCardAction.ToggleShortBroadcast(on))
        },
        dialogContent = { onDismiss ->
            BroadcastIntentDialog(
                title = stringResource(R.string.short_button_press),
                actionString = BroadcastSpec.Short.action,
                extras = pressCards.shortBroadcast.extras,
                onSave = { extras ->
                    settingsViewModel.onPressCardAction(PressCardAction.SaveShortExtras(extras))
                    onDismiss()
                },
                onSendTest = { extras ->
                    settingsViewModel.sendTestBroadcast(BroadcastSpec.Short, extras)
                },
                onDismiss = onDismiss,
            )
        }
    )

    PressCard(
        title = stringResource(R.string.long_button_press),
        subtitle = BroadcastSpec.Long.action,
        enabled = pressCards.longBroadcast.enabled,
        onEnabledChange = { on ->
            settingsViewModel.onPressCardAction(PressCardAction.ToggleLongBroadcast(on))
        },
        dialogContent = { onDismiss ->
            BroadcastIntentDialog(
                title = stringResource(R.string.long_button_press),
                actionString = BroadcastSpec.Long.action,
                extras = pressCards.longBroadcast.extras,
                onSave = { extras ->
                    settingsViewModel.onPressCardAction(PressCardAction.SaveLongExtras(extras))
                    onDismiss()
                },
                onSendTest = { extras ->
                    settingsViewModel.sendTestBroadcast(BroadcastSpec.Long, extras)
                },
                onDismiss = onDismiss,
            )
        }
    )

    PressCard(
        title = stringResource(R.string.find_my_phone),
        subtitle = stringResource(R.string.find_phone_card_subtitle),
        enabled = pressCards.findPhone.enabled,
        onEnabledChange = { on ->
            settingsViewModel.onPressCardAction(PressCardAction.ToggleFindPhone(on))
        },
        dialogContent = { onDismiss ->
            FindPhoneConfigDialog(
                volumePercent = pressCards.findPhone.volumePercent,
                melody = pressCards.findPhone.melody,
                onDismiss = onDismiss,
                onTest = settingsViewModel::testFindPhoneConfig,
                onStopTest = settingsViewModel::stopFindPhoneTest,
                onSave = { volume, melody ->
                    settingsViewModel.onPressCardAction(
                        PressCardAction.SaveFindPhoneConfig(volume, melody)
                    )
                    onDismiss()
                }
            )
        }
    )

    HorizontalDivider()
    // ── Existing settings rows ─────────────────────────────────────────────

    SettingsSectionTitle(stringResource(R.string.settings_section_locale))
    Locale(settingsViewModel::onSettingUpdated)

    SettingsSectionTitle(stringResource(R.string.settings_section_watch_behavior))
    OperationalTone(settingsViewModel::onSettingUpdated)
    Light(settingsViewModel::onSettingUpdated)
    if (WatchInfo.hasPowerSavingMode) {
        PowerSavings(settingsViewModel::onSettingUpdated)
    }
    if (WatchInfo.hasMultipleFonts) {
        Font(settingsViewModel::onSettingUpdated)
    }

    SettingsSectionTitle(
        text = stringResource(R.string.settings_section_sync),
        highlighted = highlightSync,
        modifier = Modifier.onGloballyPositioned { onSyncSectionPositioned(it.positionInParent().y.toInt()) },
    )
    TimeAdjustment(settingsViewModel::onSettingUpdated)
    SyncHistoryRow(onNavigate = onNavigateToHistory)

    SettingsSectionTitle(stringResource(R.string.settings_section_advanced))
    NtpTimeSettingsCard(settingsViewModel)
    BackupSection()
}

@Composable
private fun SettingsSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    AppText(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else androidx.compose.ui.graphics.Color.Transparent,
                shape = MaterialTheme.shapes.small,
            )
            .padding(start = Spacing.xs, top = Spacing.md, bottom = Spacing.xxs)
    )
}

@Composable
fun BottomRow(modifier: Modifier, settingsViewModel: SettingsViewModel = hiltViewModel()) {
    val isConnected by settingsViewModel.isConnected.collectAsState()
    if (!isConnected) return

    SendToWatchActionBar(
        onClick = { settingsViewModel.sendToWatch() },
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun PreviewSettingsScreen() {
    SettingsScreenPreviewContent()
}

@Composable
private fun SettingsScreenPreviewContent() {
    CassiopeiaWatchTheme(dynamicColor = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScreenTitle(
                    stringResource(id = R.string.settings),
                    Modifier,
                    showStatusBadge = false,
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    SettingsSectionTitle(stringResource(R.string.settings_section_actions))
                    PressCard(
                        title = stringResource(R.string.short_button_press),
                        subtitle = BroadcastSpec.Short.action,
                        enabled = true,
                        onEnabledChange = {},
                    )
                    PressCard(
                        title = stringResource(R.string.find_my_phone),
                        subtitle = stringResource(R.string.find_phone_card_subtitle),
                        enabled = false,
                        onEnabledChange = {},
                    )

                    HorizontalDivider()

                    SettingsSectionTitle(stringResource(R.string.settings_section_sync))
                    TimeAdjustmentPreviewRow()
                    SyncHistoryPreviewRow()

                    SettingsSectionTitle(stringResource(R.string.settings_section_advanced))
                    NtpTimePreviewRow()
                }

                SendToWatchActionBar(
                    onClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun TimeAdjustmentPreviewRow() {
    SettingCard { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            AppText(
                text = stringResource(R.string.time_adjustment),
                style = MaterialTheme.typography.titleMedium
            )
            AppText(
                text = "+0.5s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SyncHistoryPreviewRow() {
    SettingCard { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            AppText(
                text = stringResource(R.string.sync_history_title),
                style = MaterialTheme.typography.titleMedium
            )
            AppText(
                text = stringResource(R.string.sync_history_preview_last_sync),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NtpTimePreviewRow() {
    SettingCard { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            AppText(
                text = stringResource(R.string.ntp_time_title),
                style = MaterialTheme.typography.titleMedium
            )
            AppText(
                text = NtpTimeSettings.DEFAULT_SERVER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
