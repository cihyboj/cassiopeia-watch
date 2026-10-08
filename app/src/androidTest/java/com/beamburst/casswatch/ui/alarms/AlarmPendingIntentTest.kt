package com.beamburst.casswatch.ui.alarms

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beamburst.casswatch.utils.LocalDataStorage
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** Requires a device: these use Android's actual token matching and broadcast delivery. */
@RunWith(AndroidJUnit4::class)
class AlarmPendingIntentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val a = AlarmKey("AA:BB:CC:DD:FA:01", 0)
    private val b = AlarmKey("AA:BB:CC:DD:FA:02", 0)
    private val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    private fun pending(key: AlarmKey, expiry: Boolean = false) =
        PendingIntent.getBroadcast(context, key.slot, fallbackIntent(context, key, expiry), flags)

    @Test fun watchAndExpiryIdentityAreDistinctButExtrasDoNotChangeIdentity() {
        val first = pending(a)
        val other = pending(b)
        val expiry = pending(a, expiry = true)
        try {
            val updated = PendingIntent.getBroadcast(context, a.slot,
                fallbackIntent(context, a).putExtra(PhoneFallbackReceiver.EXTRA_FIRE_INSTANT, 999L), flags)
            assertEquals(first, updated)
            assertNotEquals(first, other)
            assertNotEquals(first, expiry)
            first.cancel()
            assertNull(PendingIntent.getBroadcast(context, a.slot, fallbackIntent(context, a),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
            assertNotNull(PendingIntent.getBroadcast(context, b.slot, fallbackIntent(context, b),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        } finally {
            first.cancel(); other.cancel(); expiry.cancel()
        }
    }

    @Test fun legacyCancellationMatchesNullActionAndDataWithoutCancellingNewToken() {
        val legacy = PendingIntent.getBroadcast(context, a.slot, Intent(context, PhoneFallbackReceiver::class.java), flags)
        val current = pending(a)
        try {
            PhoneFallbackScheduler(context, AlarmSchedulingTime(Clock.systemUTC(), AlarmZoneProvider(ZoneId::systemDefault))).cancelLegacy(a.slot)
            assertNull(PendingIntent.getBroadcast(context, a.slot, Intent(context, PhoneFallbackReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
            assertNotNull(PendingIntent.getBroadcast(context, a.slot, fallbackIntent(context, a),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE))
        } finally {
            legacy.cancel(); current.cancel()
        }
    }

    @Test fun explicitDataBroadcastIsDeliveredToProductionExpiryReceiver() = runBlocking {
        val coordinator = EntryPointAccessors.fromApplication(context, PhoneFallbackReceiver.AlarmEntryPoint::class.java)
            .alarmSchedulingCoordinator()
        coordinator.reconcileAll()
        LocalDataStorage.addDeviceAddress(context, a.normalizedAddress)
        val state = AlarmState(listOf(AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Test",
            oneShotLocalDate = LocalDate.now().minusDays(1).toString())), mapOf(0 to emptySet()))
        val expiry = pending(a, expiry = true)
        try {
            // Write after initialization so only actual receiver delivery consumes it.
            AlarmSyncStorage.saveState(context, a.normalizedAddress, state)
            assertNull(AlarmSyncStorage.loadState(context, a.normalizedAddress).alarms[0].firedAt)
            expiry.send()
            withTimeout(15_000) {
                while (AlarmSyncStorage.loadState(context, a.normalizedAddress).alarms[0].firedAt == null) delay(50)
            }
            assertNotNull(AlarmSyncStorage.loadState(context, a.normalizedAddress).alarms[0].firedAt)
        } finally {
            expiry.cancel()
            coordinator.forgetWatch(a.normalizedAddress) {
                LocalDataStorage.removeDeviceAddress(context, a.normalizedAddress)
                LocalDataStorage.deleteAsync(context, AlarmSyncStorage.stateKey(a.normalizedAddress))
            }
        }
    }
    @Test fun staleFallbackBroadcastFinishesWithinAndroidReceiverBudget() = runBlocking {
        val coordinator = EntryPointAccessors.fromApplication(context, PhoneFallbackReceiver.AlarmEntryPoint::class.java)
            .alarmSchedulingCoordinator()
        coordinator.reconcileAll()
        LocalDataStorage.addDeviceAddress(context, a.normalizedAddress)
        val due = System.currentTimeMillis() - 60_000
        val tomorrow = LocalDate.now().plusDays(1).toString()
        AlarmSyncStorage.saveState(context, a.normalizedAddress, AlarmState(listOf(
            AlarmSyncStorage.StoredAlarm(7, 0, true, false, "Test", oneShotLocalDate = tomorrow)), mapOf(0 to emptySet())))
        val callback = PendingIntent.getBroadcast(context, a.slot, fallbackIntent(context, a)
            .putExtra(PhoneFallbackReceiver.EXTRA_FIRE_INSTANT, due)
            .putExtra(PhoneFallbackReceiver.EXTRA_EXPECTED_HASH, "07:00:true"), flags)
        try {
            val finished = CountDownLatch(1)
            callback.send(context, 0, null, { _, _, _, _, _ -> finished.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue("goAsync receiver must finish", finished.await(10, TimeUnit.SECONDS))
            assertNull(AlarmSyncStorage.loadState(context, a.normalizedAddress).alarms[0].fallbackAttemptAt)
        } finally {
            callback.cancel()
            coordinator.forgetWatch(a.normalizedAddress) {
                LocalDataStorage.removeDeviceAddress(context, a.normalizedAddress)
                LocalDataStorage.deleteAsync(context, AlarmSyncStorage.stateKey(a.normalizedAddress))
            }
        }
    }
}
