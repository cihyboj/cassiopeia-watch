package com.beamburst.casswatch.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppSwitch
import com.beamburst.casswatch.ui.common.AppText
import com.beamburst.casswatch.ui.common.AppTextLink
import com.beamburst.casswatch.utils.NtpTimeSettings

@Composable
fun NtpTimeSettingsCard(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val ntpTime by settingsViewModel.ntpTime.collectAsState()
    var showServerDialog by remember { mutableStateOf(false) }

    SettingCard(modifier = Modifier.fillMaxWidth()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AppText(
                        text = stringResource(R.string.ntp_time_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    AppText(
                        text = stringResource(R.string.ntp_time_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppSwitch(
                    checked = ntpTime.enabled,
                    onCheckedChange = settingsViewModel::setNtpTimeEnabled,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showServerDialog = true }
                    .padding(top = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsLabel(
                    text = stringResource(R.string.ntp_time_server),
                    modifier = Modifier.padding(end = Spacing.sm),
                )
                AppTextLink(
                    text = ntpTime.server,
                    modifier = Modifier
                        .weight(1f)
                        .padding(Spacing.sm),
                    textAlign = TextAlign.End,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        }
    }

    if (showServerDialog) {
        NtpServerDialog(
            server = ntpTime.server,
            onDismiss = { showServerDialog = false },
            onConfirm = { rawServer ->
                if (settingsViewModel.setNtpTimeServer(rawServer)) {
                    showServerDialog = false
                }
            },
        )
    }
}

@Composable
private fun NtpServerDialog(
    server: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(server) { mutableStateOf(server) }
    val invalid = NtpTimeSettings.normalizeServer(value) == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ntp_time_server_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.ntp_time_server)) },
                    singleLine = true,
                    isError = invalid,
                    supportingText = {
                        Text(
                            if (invalid) {
                                stringResource(R.string.ntp_time_server_invalid)
                            } else {
                                stringResource(
                                    R.string.ntp_time_server_blank_default,
                                    NtpTimeSettings.DEFAULT_SERVER,
                                )
                            }
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = !invalid,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
