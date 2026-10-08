package com.beamburst.casswatch

import CachedIO

import com.beamburst.casswatch.pairing.WatchResponseTimeoutException
import com.beamburst.casswatch.pairing.guardWrites
import com.beamburst.casswatch.pairing.WatchConnectCoordinator
import com.beamburst.casswatch.utils.AppScopes
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.provider.Settings
import android.app.AlertDialog
import kotlinx.coroutines.launch
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.local.WatchSettingsCache
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
    private val connectionTracker: WatchConnectionTracker,
    private val connectionSession: WatchConnectionSession,
    private val connectCoordinator: WatchConnectCoordinator,
) {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "MainEventHandler"
    }

    private val connectionEvents = connectCoordinator.connectionEvents

    /** Generations for which a cache-warm has been dispatched, to keep it one-shot per connect. */
    private val warmedDispatchedFor = mutableSetOf<String>()
    private val warmedLock = Any()

    fun setupEventSubscription() {
        connectionSession.onEnded { ended ->
            sessionUiBridge.emitDisconnected()
            connectionTracker.mark(ended.address, WatchConnectionTracker.ConnectionState.DISCONNECTED)
            logConnection(SyncTrigger.CONNECTION_DISCONNECTED, true, address = ended.address)
        }
        val eventActions = arrayOf(
            EventAction("ConnectionSetupComplete") { safely("ConnectionSetupComplete") { handleConnectionSetup() } },
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

    @SuppressLint("MissingPermission")
    private fun handleConnectionSetup() {
        val observed = connectionSession.ready
        val connected = repository.isConnected()
        connectionEvents.observeTransport(connected, observed)
        if (!connected) return
        val device = ProgressEvents.getPayload("ConnectionSetupComplete") as? BluetoothDevice
        if (device == null) return
        val ready = connectionEvents.setup(device.address, device)
        if (ready == null) {
            Timber.w("Rejected late connection setup for %s", device.address)
            val rejectedState = connectionSession.state.value
            val rejectedGeneration = connectionSession.lastGeneration
            AppScopes.io.launch { connectCoordinator.closeRejectedSetup(rejectedState, rejectedGeneration) }
            return
        }
        if (connectionEvents.needsInitialization) {
            // IO.init is a no-op in the pinned library. Autonomous reconnect has no
            // waitForConnection caller to guarantee its CachedIO reset before init.
            CachedIO.clearCache()
            AppScopes.io.launch {
                runCatching {
                    connectCoordinator.withSession(ready) { check -> repository.guardWrites(check).init() }
                }.onFailure {
                    Timber.w(it, "Reconnect initialization failed for %s", ready.address)
                    if (it is WatchResponseTimeoutException) {
                        recordReconnectTimeout(ready, it)
                    } else if (connectionSession.isCurrent(ready)) {
                        connectCoordinator.disconnect(ready.address)
                    }
                }
            }
        }
        logConnection(SyncTrigger.CONNECTION_CONNECTED, true, address = ready.address)
    }

    private fun handleWatchInitialization() {
        if (connectionSession.ready == null || !repository.isConnected()) return
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
     * BLE reads). Calling the session-bound primeTimeCache right after initialization — while
     * the watch is still in its connect animation — moves the cost off the user-visible
     * sync path so later `Send Time` writes hit the fast Mode-2 path (~50 ms accuracy)
     * without ever flashing an incorrect time on the watch.
     */
    private fun dispatchCacheWarm() {
        val ready = connectionSession.ready ?: return
        val address = ready.address
        AppScopes.io.launch {
            runCatching {
                // Reconnect auto-sync already primes the cache. Dirty settings must be
                // reconciled before time I/O; preference and cache reads stay off main.
                val watchSettings = WatchSettingsCache.load(context, address)
                val autoSyncOnReconnect = LocalDataStorage.resolveAutoSyncOnReconnect(
                    stored = LocalDataStorage.getAutoSyncOnReconnect(context, address),
                    seed = watchSettings?.timeAdjustment,
                )
                if (autoSyncOnReconnect || WatchSettingsCache.isDirty(context, address)) {
                    return@runCatching
                }
                // AutoSyncCoordinator handles firmware-initiated prime+write chains.
                if (repository.isAutoTimeStarted()) return@runCatching
                val generation = connectionTracker.connectionGeneration(address)
                val key = "$address@$generation"
                synchronized(warmedLock) {
                    if (!warmedDispatchedFor.add(key)) return@runCatching
                }
                connectCoordinator.withSession(ready) { check ->
                    repository.guardWrites(check).primeTimeCache()
                    check()
                    connectionTracker.markCacheWarmed(address)
                }
            }.onFailure {
                Timber.w(it, "Cache-warm dispatch failed")
                if (it is WatchResponseTimeoutException) recordReconnectTimeout(ready, it)
            }
        }
    }

    private suspend fun recordReconnectTimeout(
        ready: WatchConnectionSession.Ready,
        error: WatchResponseTimeoutException,
    ) {
        syncLogger.record(
            address = ready.address,
            trigger = SyncTrigger.AUTO_ON_RECONNECT,
            kinds = setOf(SyncKind.CONNECTION),
            success = false,
            errorMessage = error.message,
        )
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
        val addr = connectionSession.address ?: return ""
        return LocalDataStorage.getDeviceName(context, addr)?.removePrefix("CASIO")?.trim().orEmpty()
    }

    private fun handleAppNotification() {
        if (repository.supportsAppNotifications()) {
            val appNotification = ProgressEvents.getPayload("AppNotification") as AppNotification?
            if (appNotification != null) {
                val ready = connectionSession.ready ?: return
                AppScopes.io.launch {
                    runCatching {
                        connectCoordinator.withSession(ready) { check ->
                            repository.guardWrites(check).sendAppNotification(appNotification)
                        }
                    }.onFailure { Timber.w(it, "Notification forwarding failed") }
                }
            }
        }
    }

    private fun handleConnectionFailure() {
        Timber.e("Failed to connect to the watch")
        connectionEvents.failed { address ->
            logConnection(SyncTrigger.CONNECTION_FAILED, false, "Failed to connect", address)
        }
    }

    private fun handleError() {
        val message = ProgressEvents.getPayload("Error") as? String
            ?: context.getString(
                R.string.apierror_ensure_the_official_g_shock_app_is_not_running
            )

        AppScopes.io.launch { connectCoordinator.disconnect() }
        screenManager.showError(message)
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is declared in the manifest
    private fun handleDisconnect() {
        val disconnectedDevice = ProgressEvents.getPayload("Disconnect") as? BluetoothDevice
        val device = disconnectedDevice ?: return
        val token = connectionEvents.disconnectToken(device) ?: return
        // Invalidate at receipt, before asynchronous work or reconnect setup can run.
        connectCoordinator.handleDisconnect {
            connectionEvents.disconnect(token, repository.isConnected()) {
                device.name
                    ?.takeIf { it.isNotBlank() }
                    ?.takeIf { AppUsageState.shouldShowConnectionSnackbar() }
                    ?.let { name -> AppSnackbar(context.getString(R.string.watch_disconnected_snackbar, name)) }
            }
        }
    }

    private fun logConnection(
        trigger: SyncTrigger,
        success: Boolean,
        error: String? = null,
        address: String? = connectionSession.address,
    ) {
        AppScopes.io.launch {
            syncLogger.record(
                address = address,
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
                .setTitle(context.getString(R.string.location_services_required_title))
                .setMessage(context.getString(R.string.location_services_required_body))
                .setPositiveButton(context.getString(R.string.open_settings)) { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(context.getString(R.string.cancel), null)
                .setCancelable(false)
                .show()
        }
    }
}
