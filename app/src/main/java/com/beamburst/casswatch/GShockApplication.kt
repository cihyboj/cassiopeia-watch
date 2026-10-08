package com.beamburst.casswatch

import com.beamburst.casswatch.utils.AppScopes
import android.annotation.SuppressLint
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.MultiWatchMigrator
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.pairing.CompanionDevicePresenceMonitor
import com.beamburst.casswatch.pairing.DeviceAssociationManager
import com.beamburst.casswatch.session.WatchSessionUiBridge
import com.beamburst.casswatch.services.DeviceManager
import com.beamburst.casswatch.ui.alarms.WeeklyAlarmScheduler
import com.beamburst.casswatch.ui.alarms.AlarmSchedulingCoordinator
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.ui.common.CrashLogDialog
import com.beamburst.casswatch.ui.others.CoverScreen
import com.beamburst.casswatch.utils.ActivityProvider
import com.beamburst.casswatch.utils.CrashReportHelper
import com.beamburst.casswatch.utils.SyncHistoryLogger
import com.beamburst.casswatch.utils.WatchTimeProvider
import com.beamburst.casswatch.utils.WatchTimeWriter
import timber.log.Timber
import javax.inject.Inject

enum class AppScreen {
    INITIAL,
    CONTENT_SELECTOR,
    MAIN_NAVIGATION,
}

@HiltAndroidApp
class GShockApplication : Application(), IScreenManager {

    var currentScreen by mutableStateOf(AppScreen.INITIAL)
    var errorMessage by mutableStateOf<String?>(null)

    private lateinit var eventHandler: MainEventHandler

    @Inject
    lateinit var deviceManager: DeviceManager

    @Inject
    lateinit var repository: GShockRepository

    @Inject
    lateinit var companionDevicePresenceMonitor: CompanionDevicePresenceMonitor

    @Inject
    lateinit var deviceAssociationManager: DeviceAssociationManager

    @Inject
    lateinit var weeklyAlarmScheduler: WeeklyAlarmScheduler

    @Inject
    lateinit var alarmSchedulingCoordinator: AlarmSchedulingCoordinator

    @Inject
    lateinit var watchButtonDispatcher: WatchButtonDispatcher

    @Inject
    lateinit var autoSyncCoordinator: AutoSyncCoordinator

    @Inject
    lateinit var watchSessionUiBridge: WatchSessionUiBridge
  
    @Inject
    lateinit var syncHistoryLogger: SyncHistoryLogger

    @Inject
    lateinit var watchTimeProvider: WatchTimeProvider

    @Inject
    lateinit var watchTimeWriter: WatchTimeWriter

    @Inject
    lateinit var watchSelector: CurrentWatchSelector

    @Inject
    lateinit var connectionTracker: WatchConnectionTracker

    @Inject
    lateinit var connectionSession: WatchConnectionSession

    fun init() {
        Timber.i("Initializing GShockApplication")
        weeklyAlarmScheduler.ensureInitialized()
        watchTimeProvider.refreshNtpInBackground()
        AppScopes.io.launch {
            deviceAssociationManager.init()
        }
    }


    @SuppressLint("NewApi")
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        installGlobalExceptionHandler()
        val storageMigration = MultiWatchMigrator.migrateIfNeeded(this)
        AppScopes.io.launch {
            storageMigration?.join()
            alarmSchedulingCoordinator.reconcileAll()
        }
        ActivityProvider.initialize(this)
        eventHandler =
            MainEventHandler(
                context = this,
                repository = repository,
                screenManager = this,
                sessionUiBridge = watchSessionUiBridge,
                syncLogger = syncHistoryLogger,
                watchTimeProvider = watchTimeProvider,
                watchTimeWriter = watchTimeWriter,
                watchSelector = watchSelector,
                connectionTracker = connectionTracker,
                connectionSession = connectionSession,
            )
        eventHandler.setupEventSubscription()
    }

    /**
     * Installed once from the Application (not the Activity): an Activity-installed handler
     * leaks the Activity into a process-wide static and chains a new handler on every
     * recreation.
     */
    private fun installGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Timber.e(throwable, "Uncaught exception on thread: ${thread.name}")
                if (isPairingRelated(throwable)) {
                    // Sets the pairing-crash flag so DeviceAssociationManager can run its
                    // recovery path and surface the log on next launch.
                    CrashReportHelper.logPairingCrash(this, throwable)
                } else {
                    CrashReportHelper.logCrash(
                        this,
                        throwable,
                        "Uncaught exception on thread: ${thread.name}"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to log crash")
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun isPairingRelated(throwable: Throwable): Boolean {
        var cause: Throwable? = throwable
        while (cause != null) {
            if (cause.stackTrace.any {
                    it.className.contains(".pairing.") ||
                        it.className.contains("CompanionDevice")
                }
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }


    // ScreenManager implementation
    override fun showContentSelector(repository: GShockRepository) {
        currentScreen = AppScreen.CONTENT_SELECTOR
    }

    private fun goToNavigationScreen() {
        currentScreen = AppScreen.MAIN_NAVIGATION
    }

    override fun showPreConnectionScreen() {
        currentScreen = AppScreen.MAIN_NAVIGATION
    }

    override fun showInitialScreen() {
        currentScreen = AppScreen.INITIAL
    }

    override fun showError(message: String) {
        errorMessage = message
        currentScreen = AppScreen.MAIN_NAVIGATION
        AppSnackbar(message)
    }

    @Composable
    fun StartScreen(contentPadding: PaddingValues, content: @Composable () -> Unit) {
        deviceAssociationManager.pendingCrashLog?.let { log ->
            CrashLogDialog(
                crashLog = log,
                onDismiss = { deviceAssociationManager.pendingCrashLog = null })
        }

        Box(modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)) { content() }
    }


    @Composable
    fun AppContainer(contentPadding: PaddingValues) {
        StartScreen(contentPadding) {
            // A single BottomNavigationBarWithPermissions call site: screen transitions
            // previously rendered it from five different branches, so every transition
            // rebuilt the whole nav subtree (losing the selected tab and restarting the
            // inactivity timer).
            val showCover = currentScreen == AppScreen.CONTENT_SELECTOR &&
                repository.isAlwaysConnectedConnectionPressed()
            if (showCover) {
                // Recompose the cover's connection indicator when tracked state changes;
                // repository.isConnected() alone is not observable.
                val trackerStates by connectionTracker.states.collectAsState()
                val isConnected = remember(trackerStates) { repository.isConnected() }
                CoverScreen(onUnlock = { goToNavigationScreen() }, isConnected = isConnected)
            } else {
                BottomNavigationBarWithPermissions(repository = repository)
            }
        }
        if (currentScreen == AppScreen.INITIAL) {
            LaunchedEffect(Unit) {
                deviceAssociationManager.checkPairedDevicesOrNotify()
            }
        }
    }
}
