package com.beamburst.casswatch.utils

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPermissionPolicyTest {
    @Test
    fun `Android 8 and 9 require coarse location for scanning`() {
        for (sdk in 26..28) {
            assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION), essentialWatchPermissions(sdk))
        }
    }

    @Test
    fun `Android 10 and 11 require fine location for scanning`() {
        for (sdk in 29..30) {
            assertEquals(
                listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                essentialWatchPermissions(sdk),
            )
        }
    }

    @Test
    fun `Android 12 and newer require Bluetooth only`() {
        for (sdk in 31..36) {
            assertEquals(
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                essentialWatchPermissions(sdk),
            )
        }
    }

    @Test
    fun `notification denial never blocks setup or consults its rationale`() {
        val required = essentialWatchPermissions(33)
        val grants = required.associateWith { true } + (Manifest.permission.POST_NOTIFICATIONS to false)
        assertEquals(WatchPermissionDecision.GRANTED, watchPermissionDecision(required, grants) {
            error("No essential permission is denied")
        })
    }

    @Test
    fun `Bluetooth denial needs retry only when rationale is available`() {
        val required = essentialWatchPermissions(33)
        val grants = required.associateWith { it != Manifest.permission.BLUETOOTH_CONNECT }
        assertEquals(WatchPermissionDecision.RETRY, watchPermissionDecision(required, grants) { true })
        assertEquals(WatchPermissionDecision.SETTINGS, watchPermissionDecision(required, grants) { false })
    }

    @Test
    fun `missing result never grants essential permissions`() {
        assertEquals(WatchPermissionDecision.SETTINGS, watchPermissionDecision(essentialWatchPermissions(33), emptyMap()) { false })
    }

    @Test
    fun `fine location denial blocks Android 10 and 11 scanning`() {
        for (sdk in 29..30) {
            val grants = mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true)
            assertEquals(WatchPermissionDecision.SETTINGS, watchPermissionDecision(essentialWatchPermissions(sdk), grants) { false })
        }
    }

    @Test
    fun `notification denial does not prompt again when Bluetooth is granted`() {
        val grants = essentialWatchPermissions(33).associateWith { true } + (Manifest.permission.POST_NOTIFICATIONS to false)
        assertTrue(initialWatchPermissionRequest(33, grants).isEmpty())
    }

    @Test
    fun `initial request includes all essential permissions and optionally notifications`() {
        assertEquals(essentialWatchPermissions(32), initialWatchPermissionRequest(32, emptyMap()))
        val request = initialWatchPermissionRequest(33, mapOf(Manifest.permission.BLUETOOTH_SCAN to true))
        assertEquals(essentialWatchPermissions(33) + Manifest.permission.POST_NOTIFICATIONS, request)
        assertFalse(essentialWatchPermissions(33).contains(Manifest.permission.POST_NOTIFICATIONS))
    }
}
