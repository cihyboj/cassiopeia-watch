package com.beamburst.casswatch.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.BroadcastExtra
import com.beamburst.casswatch.data.local.ExtraType
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppButton
import com.beamburst.casswatch.ui.common.AppText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastIntentDialog(
    title: String,
    actionString: String,
    extras: List<BroadcastExtra>,
    onSave: (List<BroadcastExtra>) -> Unit,
    onSendTest: (List<BroadcastExtra>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var draftExtras by remember { mutableStateOf(extras) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AppText(
                    text = stringResource(R.string.broadcast_action_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = actionString,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                AppButton(
                    text = stringResource(R.string.broadcast_copy_action),
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("broadcast_action", actionString))
                    }
                )

                AppText(
                    text = stringResource(R.string.broadcast_extras_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                draftExtras.forEachIndexed { index, extra ->
                    ExtraRow(
                        extra = extra,
                        onChanged = { updated ->
                            draftExtras = draftExtras.toMutableList().also { it[index] = updated }
                        },
                        onDelete = {
                            draftExtras = draftExtras.toMutableList().also { it.removeAt(index) }
                        }
                    )
                }
                AppButton(
                    text = stringResource(R.string.broadcast_add_extra),
                    onClick = {
                        draftExtras = draftExtras + BroadcastExtra("", ExtraType.STRING, "")
                    }
                )
                AppButton(
                    text = stringResource(R.string.broadcast_send_test),
                    onClick = { onSendTest(draftExtras) }
                )

                AppText(
                    text = stringResource(R.string.broadcast_how_to_receive),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppText(
                    text = stringResource(R.string.broadcast_help_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            AppButton(
                text = stringResource(android.R.string.ok),
                onClick = { onSave(draftExtras) },
            )
        },
        dismissButton = {
            AppButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtraRow(
    extra: BroadcastExtra,
    onChanged: (BroadcastExtra) -> Unit,
    onDelete: () -> Unit,
) {
    var typeExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = extra.key,
            onValueChange = { onChanged(extra.copy(key = it)) },
            placeholder = { Text("key") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = it },
            modifier = Modifier.width(90.dp),
        ) {
            OutlinedTextField(
                value = extra.type.name,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                modifier = Modifier.menuAnchor(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
            ) {
                ExtraType.values().forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.name) },
                        onClick = { onChanged(extra.copy(type = t)); typeExpanded = false },
                    )
                }
            }
        }
        OutlinedTextField(
            value = extra.value,
            onValueChange = { onChanged(extra.copy(value = it)) },
            placeholder = { Text("value") },
            modifier = Modifier.weight(1.2f),
            singleLine = true,
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Close, contentDescription = "Remove extra")
        }
    }
}
