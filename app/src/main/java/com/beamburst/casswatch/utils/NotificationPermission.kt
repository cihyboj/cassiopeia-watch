package com.beamburst.casswatch.utils

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beamburst.casswatch.R
import com.beamburst.casswatch.ui.common.AppButton
import com.beamburst.casswatch.ui.common.AppText

internal fun canPostNotifications(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()

// Legacy channel used by NotificationProvider for auto-sync outcomes.
private const val SYNC_NOTIFICATION_CHANNEL = "G_SHOCK_SMART_SYNC_CHANNEL"

private fun canPostSyncNotifications(context: Context): Boolean {
    if (!canPostNotifications(context)) return false
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = manager.getNotificationChannel(SYNC_NOTIFICATION_CHANNEL) ?: return true
    if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
    val group = channel.group ?: return true
    return Build.VERSION.SDK_INT < 28 || manager.getNotificationChannelGroup(group)?.isBlocked != true
}

/** Keep the preference enabled, explaining how to unblock delivery when Android blocks it. */
@Composable
internal fun rememberSyncNotificationRequest(enabled: Boolean): () -> Unit {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentEnabled = rememberUpdatedState(enabled)
    var showExplanation by remember { mutableStateOf(false) }
    var requestPending by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        requestPending = false
        showExplanation = !canPostSyncNotifications(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !requestPending) {
                showExplanation = currentEnabled.value && !canPostSyncNotifications(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showExplanation) {
        AlertDialog(
            onDismissRequest = { showExplanation = false },
            text = { AppText(stringResource(R.string.sync_notifications_blocked)) },
            confirmButton = {
                AppButton(
                    stringResource(R.string.open_settings),
                    onClick = {
                        showExplanation = false
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    },
                )
            },
            dismissButton = {
                AppButton(
                    stringResource(R.string.cancel),
                    onClick = { showExplanation = false },
                )
            },
        )
    }

    return {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPending = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showExplanation = !canPostSyncNotifications(context)
        }
    }
}
