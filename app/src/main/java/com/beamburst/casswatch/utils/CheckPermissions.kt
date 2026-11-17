// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.utils

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beamburst.casswatch.R
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppButton
import com.beamburst.casswatch.ui.common.AppText

@Composable
fun CheckPermissions(onPermissionsGranted: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val requiredPermissions = remember { essentialWatchPermissions(Build.VERSION.SDK_INT) }
    var decision by remember { mutableStateOf(WatchPermissionDecision.RETRY) }

    fun refreshPermissions() {
        val grants = requiredPermissions.associateWith {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        // Replacing the entire decision clears stale denial state after a Settings grant.
        decision = watchPermissionDecision(requiredPermissions, grants) {
            activity.shouldShowRequestPermissionRationale(it)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { refreshPermissions() },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        refreshPermissions()
        val grants = requiredPermissions.associateWith {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        val request = initialWatchPermissionRequest(Build.VERSION.SDK_INT, grants)
        if (request.isNotEmpty()) launcher.launch(request.toTypedArray())
    }

    if (decision == WatchPermissionDecision.GRANTED) {
        onPermissionsGranted()
    } else {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().systemBarsPadding().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppText(stringResource(R.string.permissions_required_title), style = MaterialTheme.typography.titleLarge)
                AppText(stringResource(R.string.watch_permission_explanation))
                AppButton(
                    stringResource(if (decision == WatchPermissionDecision.RETRY) R.string.retry else R.string.open_settings),
                    onClick = {
                        if (decision == WatchPermissionDecision.RETRY) {
                            launcher.launch(requiredPermissions.toTypedArray())
                        } else {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)))
                        }
                    },
                )
            }
        }
    }
}
