package com.beamburst.casswatch.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.FindPhoneMelody
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppButton

@Composable
fun FindPhoneConfigDialog(
    volumePercent: Int,
    melody: FindPhoneMelody,
    onDismiss: () -> Unit,
    onSave: (volumePercent: Int, melody: FindPhoneMelody) -> Unit,
) {
    var volume by remember { mutableFloatStateOf(volumePercent.coerceIn(0, 100).toFloat()) }
    var selectedMelody by remember { mutableStateOf(melody) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.find_phone_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.find_phone_volume_label, volume.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    valueRange = 0f..100f,
                )

                Text(
                    text = stringResource(R.string.find_phone_melody_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    FindPhoneMelody.entries.forEach { m ->
                        AppButton(
                            text = stringResource(
                                when (m) {
                                    FindPhoneMelody.ALARM -> R.string.find_phone_melody_alarm
                                    FindPhoneMelody.RINGTONE -> R.string.find_phone_melody_ringtone
                                    FindPhoneMelody.NOTIFICATION -> R.string.find_phone_melody_notification
                                }
                            ),
                            onClick = { selectedMelody = m },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = Spacing.xxs)
                        )
                    }
                }
            }
        },
        confirmButton = {
            AppButton(
                text = stringResource(android.R.string.ok),
                onClick = { onSave(volume.toInt(), selectedMelody) }
            )
        },
        dismissButton = {
            AppButton(text = stringResource(android.R.string.cancel), onClick = onDismiss)
        }
    )
}
