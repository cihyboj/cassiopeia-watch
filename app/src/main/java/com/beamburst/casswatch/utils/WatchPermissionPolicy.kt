package com.beamburst.casswatch.utils

import android.Manifest
import android.annotation.SuppressLint

internal enum class WatchPermissionDecision { GRANTED, RETRY, SETTINGS }

// These are inlined permission names, selected using the caller-provided SDK version.
@SuppressLint("InlinedApi")
internal fun essentialWatchPermissions(sdkInt: Int): List<String> = when {
    sdkInt >= 31 -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    sdkInt >= 29 -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
}

internal fun watchPermissionDecision(
    requiredPermissions: List<String>,
    grants: Map<String, Boolean>,
    shouldShowRationale: (String) -> Boolean,
): WatchPermissionDecision {
    val denied = requiredPermissions.filter { grants[it] != true }
    return when {
        denied.isEmpty() -> WatchPermissionDecision.GRANTED
        denied.any(shouldShowRationale) -> WatchPermissionDecision.RETRY
        else -> WatchPermissionDecision.SETTINGS
    }
}

// Watch setup requests only the permissions needed to communicate with the watch.
internal fun initialWatchPermissionRequest(sdkInt: Int, grants: Map<String, Boolean>): List<String> =
    essentialWatchPermissions(sdkInt).filter { grants[it] != true }
