package com.beamburst.casswatch

import com.beamburst.casswatch.utils.AppScopes
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.provider.Settings
import android.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.session.WatchSessionUiBridge
import com.beamburst.casswatch.services.NotificationMonitorService
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.utils.AppUsageState
import com.beamburst.casswatch.utils.ActivityProvider
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.Utils
import com.beamburst.casswatch.utils.SyncHistoryLogger
import com.beamburst.casswatch.utils.WatchTimeProvider
import com.beamburst.casswatch.utils.WatchTimeWriter
import org.avmedia.gshockapi.AppNotification
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import timber.log.Timber

class MainEventHandler(
    private val context: GShockApplication,
    private val repository: GShockRepository,
    private val screenManager: IScreenManager,
    private val sessionUiBridge: WatchSessionUiBridge,
    private val syncLogger: SyncHistoryLogger,
    private val watchTimeProvider: WatchTimeProvider,
    private val watchTimeWriter: WatchTimeWriter,
    private val watchSelector: CurrentWatchSelector,
    private val connectionTracker: WatchConnectionTracker,
) {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "MainEventHandler"
    }

    /** Generations for which a cache-warm has been dispatched, to keep it one-shot per connect. */
    private val warmedDispatchedFor = mutableSetOf<String>()
    private val warmedLock = Any()

    fun setupEventSubscription() {
        val eventActions = arrayOf(
            EventAction("ConnectionSetupComplete") { safely("ConnectionSetupComplete") { logConnection(SyncTrigger.CONNECTION_CONNECTED, true) } },
            EventAction("WatchInitializationCompleted") { safely("WatchInitializationCompleted") { handleWatchInitialization() } },
            EventAction("ConnectionFailed") { safely("ConnectionFailed") { handleConnectionFailure() } },
            EventAction("Error") { safely("Error") { handleError() } },
            EventAction("WaitForConnection") { safely("WaitForConnection") { handleWaitForConnection() } },
            EventAction("Disconnect") { safely("Disconnect") { handleDisconnect() } },
            EventAction("HomeTimeUpdated") {},
            EventAction("AppNotification") { safely("AppNotification") { handleAppNotification() } },
            EventAction("LocationServicesDisabled") { safely("LocationServicesDisabled") { handleLocationServicesDisabled() } }
        )

        ProgressEvents.runEventActions(EVENT_SUBSCRIBER_ID, eventActions)
    }

    /** Event handlers run on the shared ProgressEvents dispatch thread; a throw there must
     * never take down the process or the event pipeline for other subscribers. */
    private inline fun safely(name: String, block: () -> Unit) {
        runCatching(block).onFailure { Timber.e(it, "Event handler %s failed", name) }
    }

    private fun handleWatchInitialization() {
        if (repository.supportsAppNotifications()) {
            NotificationMonitorService.startService(context)
        }
        watchTimeProvider.refreshNtpInBackground()
        screenManager.showContentSelector(repository)
        showWatchConnectedSnackbar()
        sessionUiBridge.emitConnected()
        dispatchCacheWarm()
    }

    /**
     * Primes GShockAPI's CachedIO once per fresh connection generation. The first time
     * write on a fresh connection otherwise triggers a 2–4 s slow path (DST + WorldCities
     * BLE reads). Calling [WatchTimeWriter.primeCache] right after initialization — while
     * the watch is still in its connect animation — moves the cost off the user-visible
     * sync path so later `Send Time` writes hit the fast Mode-2 path (~50 ms accuracy)
     * without ever flashing an incorrect time on the watch.
     */
    private fun dispatchCacheWarm() {
        val address = watchSelector.currentAddress() ?: return
        // If the user has Auto-sync-on-reconnect enabled for this watch (the default when
        // unset), WeeklyAlarmScheduler will issue a (logged, user-visible) time write moments
        // later that also warms the cache. Running a second concurrent setTime over the same
        // BLE link wastes work and can confuse the watch firmware (inconsistent "OK" display,
        // slower first call).
        if (LocalDataStorage.getAutoSyncOnReconnect(context, address) != false) {
            return
        }
        // AutoSyncCoordinator.runChain() handles prime+write for firmware-initiated syncs
        if (repository.isAutoTimeStarted()) return
        val generation = connectionTracker.connectionGeneration(address)
        val key = "$address@$generation"
        synchronized(warmedLock) {
            if (!warmedDispatchedFor.add(key)) return
        }
        AppScopes.io.launch {
            runCatching {
                watchTimeWriter.primeCache(repository)
            }.onFailure { Timber.w(it, "Cache-warm dispatch failed") }
        }
    }

    private fun showWatchConnectedSnackbar() {
        if (!AppUsageState.shouldShowConnectionSnackbar()) return

        val displayName = resolveWatchDisplayNameForSnackbar()
        val message =
            if (displayName.isNotBlank()) {
                context.getString(R.string.watch_snackbar_connected, displayName)
            } else {
                context.getString(R.string.watch_snackbar_connected_no_name)
            }
        AppSnackbar(message)
    }

    private fun resolveWatchDisplayNameForSnackbar(): String {
        val stored = LocalDataStorage.get(context, "LastDeviceName", "")
        if (!stored.isNullOrBlank()) return stored.removePrefix("CASIO").trim()
        val addr = LocalDataStorage.get(context, "LastDeviceAddress", "") ?: return ""
        if (addr.isBlank()) return ""
        return LocalDataStorage.getDeviceName(context, addr)?.removePrefix("CASIO")?.trim().orEmpty()
    }

    private fun handleAppNotification() {
        if (repository.supportsAppNotifications()) {
            val appNotification = ProgressEvents.getPayload("AppNotification") as AppNotification?
            if (appNotification != null) {
                repository.sendAppNotification(appNotification)
            }
        }
    }

    private fun handleConnectionFailure() {
        Timber.e("Failed to connect to the watch")
        logConnection(SyncTrigger.CONNECTION_FAILED, false, "Failed to connect")
    }

    private fun handleError() {
        val message = ProgressEvents.getPayload("Error") as String?
            ?: context.getString(
                R.string.apierror_ensure_the_official_g_shock_app_is_not_running
            )

        repository.disconnect()
        screenManager.showError(message)
        screenManager.showPreConnectionScreen()
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is declared in the manifest
    private fun handleDisconnect() {
        sessionUiBridge.emitDisconnected()
        val disconnectedDevice = ProgressEvents.getPayload("Disconnect") as? BluetoothDevice
        disconnectedDevice?.let { device ->
            repository.teardownConnection(device)
        }
        disconnectedDevice?.name
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { AppUsageState.shouldShowConnectionSnackbar() }
            ?.let { name -> AppSnackbar("$name disconnected") }
        logConnection(SyncTrigger.CONNECTION_DISCONNECTED, true)
    }

    private fun logConnection(trigger: SyncTrigger, success: Boolean, error: String? = null) {
        AppScopes.io.launch {
            syncLogger.record(
                trigger = trigger,
                kinds = setOf(SyncKind.CONNECTION),
                success = success,
                errorMessage = error,
                details = SyncDetails.Empty.copy(connection = connectionDescription(trigger)),
            )
        }
    }

    private fun connectionDescription(trigger: SyncTrigger): String =
        when (trigger) {
            SyncTrigger.CONNECTION_CONNECTED -> "Connection setup complete"
            SyncTrigger.CONNECTION_DISCONNECTED -> "Watch disconnected"
            SyncTrigger.CONNECTION_FAILED -> "Connection failed"
            else -> trigger.name
        }

    private fun handleWaitForConnection() {
        AppScopes.default.launch {
            context.deviceAssociationManager.checkPairedDevicesOrNotify()
        }
    }

    private fun handleLocationServicesDisabled() {
        AppScopes.main.launch {
            val activity = ActivityProvider.getCurrentActivity() ?: run {
                Timber.e("No activity available to show location services dialog")
                return@launch
            }

            AlertDialog.Builder(activity)
                .setTitle("Location Services Required")
                .setMessage("Android requires Location Services to be enabled for Bluetooth scanning. This app does not use your location — it is an Android system requirement.")
                .setPositiveButton("Open Settings") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .show()
        }
    }
}
