package com.beamburst.casswatch.utils

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.beamburst.casswatch.R
import com.beamburst.casswatch.ui.common.AppButton
import com.beamburst.casswatch.ui.common.AppSnackbar
import kotlinx.coroutines.delay

@Composable
fun CheckPermissions(onPermissionsGranted: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity

    val requiredPermissions = remember { essentialWatchPermissions(Build.VERSION.SDK_INT) }

    // State to track if permissions are granted
    var permissionsGranted by remember { mutableStateOf(false) }
    var showRationaleDialog by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }

    // Launcher for requesting permissions
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val decision = watchPermissionDecision(requiredPermissions, permissions) {
                activity.shouldShowRequestPermissionRationale(it)
            }
            permissionsGranted = decision == WatchPermissionDecision.GRANTED
            showRationaleDialog = decision == WatchPermissionDecision.RETRY
            permanentlyDenied = decision == WatchPermissionDecision.SETTINGS
        }
    )

    // Check permissions before launching the request
    LaunchedEffect(Unit) {
        val granted = requiredPermissions.associateWith {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        val request = initialWatchPermissionRequest(Build.VERSION.SDK_INT, granted)
        if (request.isEmpty()) {
            permissionsGranted = true
        } else {
            launcher.launch(request.toTypedArray())
        }
    }

    // Trigger callbacks based on permission status
    if (permissionsGranted) {
        onPermissionsGranted()
    }

    // Show rationale dialog if needed
    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { /* Do nothing */ },
            title = { Text(text = stringResource(R.string.permissions_required_title)) },
            text = { Text(stringResource(R.string.permissions_required_body)) },
            confirmButton = {
                AppButton(
                    stringResource(R.string.retry),
                    onClick = {
                        showRationaleDialog = false
                        launcher.launch(requiredPermissions.toTypedArray())
                    })
            },
            dismissButton = {
                AppButton(stringResource(R.string.exit), onClick = {
                    activity.finish()  // Exit if user doesn't want to grant permissions
                })
            }
        )
    }

    if (permanentlyDenied) {
        val message = stringResource(
            R.string.permissions_are_permanently_denied_please_enable_them_in_the_app_settings
        )
        // Side effects belong in LaunchedEffect: the previous Timer-in-composition spawned
        // a new thread on every recomposition and called finish() off the main thread.
        LaunchedEffect(Unit) {
            AppSnackbar(message)
            delay(6000)
            activity.finish()
        }
    }
}

