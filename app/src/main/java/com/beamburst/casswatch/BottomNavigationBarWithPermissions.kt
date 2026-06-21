package com.beamburst.casswatch

import android.Manifest.permission.READ_CALENDAR
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.EntryPointAccessors
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.di.ApplicationContextEntryPoint
import com.beamburst.casswatch.services.InactivityHandler
import com.beamburst.casswatch.ui.alarms.AlarmsScreen
import com.beamburst.casswatch.ui.common.AppSnackbar
import com.beamburst.casswatch.ui.debug.BleDebugScreen
import com.beamburst.casswatch.ui.events.EventsScreen
import com.beamburst.casswatch.ui.history.SyncHistoryScreen
import com.beamburst.casswatch.ui.settings.SettingsScreen
import com.beamburst.casswatch.ui.time.TimeScreen
import com.beamburst.casswatch.utils.AppUsageState
import com.beamburst.casswatch.utils.LocalDataStorage
import kotlin.time.Duration.Companion.seconds

private const val DEBUG_TAB_UNLOCKED_KEY = "ble_debug_tab_unlocked"
private const val DEBUG_TAB_UNLOCK_TAPS = 7

@Composable
fun BottomNavigationBarWithPermissions(
    repository: GShockRepository,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val localContext = LocalContext.current.applicationContext
    val appContext = remember {
        EntryPointAccessors.fromApplication(
            localContext,
            ApplicationContextEntryPoint::class.java
        ).getApplicationContext()
    }
    var debugUnlocked by remember {
        mutableStateOf(
            BuildConfig.DEBUG &&
                LocalDataStorage.get(appContext, DEBUG_TAB_UNLOCKED_KEY, "false") == "true"
        )
    }
    var debugUnlockTapCount by remember { mutableIntStateOf(0) }

    val inactivityHandler = remember {
        InactivityHandler(timeout = (3 * 60).seconds) {
            repository.disconnect()
            if (AppUsageState.shouldShowConnectionSnackbar()) {
                AppSnackbar(appContext.getString(R.string.disconnected_due_to_inactivity))
            }
        }
    }

    fun navigateToTab(route: String) {
        navController.popBackStack(Screens.SyncHistory.route, inclusive = true)
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    DisposableEffect(Unit) {
        inactivityHandler.startMonitoring()
        onDispose {
            inactivityHandler.stopMonitoring()
        }
    }

    fun Modifier.detectInactivity(handler: InactivityHandler): Modifier {
        return this.pointerInput(Unit) {
            while (true) {
                awaitPointerEventScope {
                    awaitPointerEvent()
                    AppUsageState.registerInteraction()
                    handler.registerInteraction()
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .detectInactivity(inactivityHandler),
        bottomBar = {
            NavigationBar(
                modifier = Modifier,
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                BottomNavigationItem().bottomNavigationItems(showDebug = debugUnlocked)
                    .forEachIndexed { _, navigationItem ->
                        NavigationBarItem(
                            selected = navigationItem.route == currentDestination?.route?.substringBefore("?"),
                            label = {
                                Text(
                                    text = navigationItem.label,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            icon = {
                                Icon(
                                    navigationItem.icon,
                                    contentDescription = navigationItem.label
                                )
                            },
                            onClick = {
                                navigateToTab(navigationItem.route)
                            },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screens.Time.route,
            modifier = Modifier.padding(paddingValues = paddingValues)
        ) {
            composable(Screens.Time.route) {
                TimeScreen()
            }
            composable(Screens.Alarms.route) {
                AlarmsScreen(
                    onNavigateToHistory = {
                        navController.navigate(Screens.SyncHistory.route) {
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    onOpenSyncSettings = {
                        navController.navigate(Screens.Settings.withAnchor("sync")) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = false
                        }
                    }
                )
            }
            composable(Screens.Events.route) {
                NavigateWithPermissions(
                    listOf(READ_CALENDAR),
                    navController,
                    destinationScreen = { EventsScreen() },
                    stringResource(
                        R.string.calendar_permission_denied_cannot_access_events,
                    )
                )
            }
            if (BuildConfig.DEBUG && debugUnlocked) {
                composable(Screens.Debug.route) {
                    BleDebugScreen()
                }
            }
            composable(Screens.Settings.route) {
                SettingsScreen(
                    onNavigateToHistory = {
                        navController.navigate(Screens.SyncHistory.route) {
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    onDebugHeaderTap = debugHeaderTapHandler(
                        debugUnlocked = debugUnlocked,
                        debugUnlockTapCount = debugUnlockTapCount,
                        onTapCountChange = { debugUnlockTapCount = it },
                        onUnlocked = {
                            debugUnlocked = true
                            LocalDataStorage.put(appContext, DEBUG_TAB_UNLOCKED_KEY, "true")
                            AppSnackbar("BLE Debug unlocked")
                        },
                    ),
                )
            }
            composable(
                route = Screens.Settings.routeWithAnchor,
                arguments = listOf(
                    navArgument("anchor") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
            ) { entry ->
                SettingsScreen(
                    anchor = entry.arguments?.getString("anchor"),
                    onNavigateToHistory = {
                        navController.navigate(Screens.SyncHistory.route) {
                            launchSingleTop = true
                            restoreState = false
                        }
                    },
                    onDebugHeaderTap = debugHeaderTapHandler(
                        debugUnlocked = debugUnlocked,
                        debugUnlockTapCount = debugUnlockTapCount,
                        onTapCountChange = { debugUnlockTapCount = it },
                        onUnlocked = {
                            debugUnlocked = true
                            LocalDataStorage.put(appContext, DEBUG_TAB_UNLOCKED_KEY, "true")
                            AppSnackbar("BLE Debug unlocked")
                        },
                    ),
                )
            }
            composable(Screens.SyncHistory.route) {
                SyncHistoryScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun debugHeaderTapHandler(
    debugUnlocked: Boolean,
    debugUnlockTapCount: Int,
    onTapCountChange: (Int) -> Unit,
    onUnlocked: () -> Unit,
): (() -> Unit)? {
    if (!BuildConfig.DEBUG) return null
    return {
        if (debugUnlocked) {
            AppSnackbar("BLE Debug already unlocked")
        } else {
            val newCount = debugUnlockTapCount + 1
            onTapCountChange(newCount)
            val remaining = DEBUG_TAB_UNLOCK_TAPS - newCount
            if (remaining <= 0) {
                onUnlocked()
            } else if (remaining <= 3) {
                AppSnackbar("$remaining taps to unlock BLE Debug")
            }
        }
    }
}

@Composable
fun NavigateWithPermissions(
    requiredPermissions: List<String>,
    navController: NavController,
    destinationScreen: @Composable () -> Unit,
    errorMessage: String = stringResource(R.string.required_permissions_denied_cannot_access_screen),
) {
    var hasNavigated by remember { mutableStateOf(false) }
    var showSnackbar by remember { mutableStateOf(false) }

    // Resolve the string safely here in the Composable scope
    val additionalInfo = stringResource(R.string.clear_app_storage_from_android_setting_and_restart_the_app_to_add_permissions)
    val fullErrorMessage = remember(errorMessage, additionalInfo) { errorMessage + additionalInfo }

    if (requiredPermissions.isEmpty()) return

    // Render the Snackbar in the UI tree, not in the effect
    if (showSnackbar) {
        AppSnackbar(fullErrorMessage)
    }

    PermissionRequiredScreen(
        requiredPermissions = requiredPermissions,
        onPermissionGranted = { destinationScreen() },
        onPermissionDenied = {
            if (!hasNavigated) {
                hasNavigated = true

                // Use SideEffect or just set state to trigger the snackbar
                showSnackbar = true

                navController.navigate(Screens.Time.route) {
                    popUpTo(Screens.Time.route) { inclusive = true }
                }
            }
        }
    )
}

@Composable
fun PermissionRequiredScreen(
    requiredPermissions: List<String>,
    onPermissionGranted: @Composable () -> Unit,
    onPermissionDenied: @Composable () -> Unit
) {
    var permissionGranted by remember { mutableStateOf(false) }
    var permissionChecked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            permissionGranted = permissions.values.all { it }
            permissionChecked = true
        }
    )

    LaunchedEffect(Unit) {
        launcher.launch(requiredPermissions.toTypedArray())
    }

    if (permissionChecked) {
        if (permissionGranted) {
            onPermissionGranted() // Safe to call @Composable here
        } else {
            onPermissionDenied()  // Safe to call @Composable here
        }
    }
}
