package com.beamburst.casswatch.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.beamburst.casswatch.R
import com.beamburst.casswatch.backup.BackupExporter
import com.beamburst.casswatch.backup.BackupImporter
import com.beamburst.casswatch.backup.ImportResult
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppCard
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.ui.common.AppText
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // rememberSaveable so the dialog survives the activity restart that the SAF picker can
    // trigger on some devices (the picker briefly puts the host activity into the background
    // and Android may recreate it before our callback fires).
    var importSuccessCount by rememberSaveable { mutableStateOf<Int?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { BackupExporter.export(context, uri) }
                .onSuccess { count ->
                    AppSnackbar(context.getString(R.string.backup_export_success, count))
                }
                .onFailure { error ->
                    AppSnackbar(
                        context.getString(
                            R.string.backup_export_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = BackupImporter.import(context, uri)) {
                is ImportResult.Ok -> {
                    // Success goes through a modal dialog (not a snackbar) because the
                    // message is long, contains an explicit follow-up action (Restart),
                    // and must stay on screen until the user acknowledges it.
                    importSuccessCount = result.entryCount
                }
                is ImportResult.Malformed ->
                    AppSnackbar(context.getString(R.string.backup_import_malformed, result.message))
                is ImportResult.UnsupportedVersion ->
                    AppSnackbar(
                        context.getString(R.string.backup_import_version_mismatch, result.found),
                    )
                is ImportResult.IoError ->
                    AppSnackbar(context.getString(R.string.backup_import_failed, result.message))
            }
        }
    }

    importSuccessCount?.let { count ->
        AlertDialog(
            onDismissRequest = { importSuccessCount = null },
            title = { AppText(text = stringResource(R.string.backup_import_dialog_title)) },
            text = {
                AppText(
                    text = stringResource(R.string.backup_import_success, count),
                )
            },
            confirmButton = {
                Button(onClick = { restartApp(context) }) {
                    AppText(text = stringResource(R.string.backup_restart_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { importSuccessCount = null }) {
                    AppText(text = stringResource(R.string.backup_import_dialog_later))
                }
            },
        )
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            AppText(
                text = stringResource(R.string.backup_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppText(
                text = stringResource(R.string.backup_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xxs, bottom = Spacing.sm),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedButton(
                    onClick = { exportLauncher.launch(defaultBackupFilename(context)) },
                ) {
                    AppText(text = stringResource(R.string.backup_export_button))
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                ) {
                    AppText(text = stringResource(R.string.backup_import_button))
                }
            }
        }
    }
}

private fun defaultBackupFilename(context: android.content.Context): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return context.getString(R.string.backup_export_default_name, stamp)
}

private fun restartApp(context: Context) {
    // Clean relaunch: start a fresh task with the launcher intent, then exit the current
    // process gracefully. Avoids Process.killProcess + Android's auto-restart of a killed
    // foreground activity, which restores stale saved-instance state and triggered ANRs
    // when the post-import state didn't match what the restored ViewModels expected.
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: return
    val restart = Intent.makeRestartActivityTask(launchIntent.component)
    restart.setPackage(context.packageName)
    context.startActivity(restart)
    Runtime.getRuntime().exit(0)
}
