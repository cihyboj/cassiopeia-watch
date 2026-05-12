package com.beamburst.casswatch.ui.settings

import android.app.Activity
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    var lastImportEntryCount by remember { mutableStateOf<Int?>(null) }

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
                    lastImportEntryCount = result.entryCount
                    AppSnackbar(context.getString(R.string.backup_import_success, result.entryCount))
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

    LaunchedEffect(Unit) {
        // No-op; ensures snackbars from a prior recomposition aren't replayed on entry.
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
            lastImportEntryCount?.let {
                Row(
                    modifier = Modifier.padding(top = Spacing.sm).fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = { restartApp(context as Activity) }) {
                        AppText(text = stringResource(R.string.backup_restart_now))
                    }
                }
            }
        }
    }
}

private fun defaultBackupFilename(context: android.content.Context): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return context.getString(R.string.backup_export_default_name, stamp)
}

private fun restartApp(activity: Activity) {
    activity.finishAffinity()
    Process.killProcess(Process.myPid())
}
