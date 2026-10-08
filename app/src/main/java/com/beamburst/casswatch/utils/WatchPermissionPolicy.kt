package com.beamburst.casswatch.utils

import android.Manifest

internal enum class WatchPermissionDecision { GRANTED, RETRY, SETTINGS }

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

// Offer notifications with initial watch setup, but never retry or gate setup on them.
internal fun initialWatchPermissionRequest(sdkInt: Int, grants: Map<String, Boolean>): List<String> {
    val required = essentialWatchPermissions(sdkInt)
    if (required.all { grants[it] == true }) return emptyList()
    return if (sdkInt >= 33) required + Manifest.permission.POST_NOTIFICATIONS else required
}
