package com.beamburst.casswatch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import org.avmedia.gshockapi.WatchInfo

data class BottomNavigationItem(
    val label: String = "",
    val icon: ImageVector = Icons.Filled.Home,
    val route: String = "",
) {
    @Composable
    fun bottomNavigationItems(showDebug: Boolean = false): List<BottomNavigationItem> {

        val baseItems = mutableListOf(
            BottomNavigationItem(
                label = stringResource(R.string.home),
                icon = Icons.Filled.Home,
                route = Screens.Time.route
            ),
            BottomNavigationItem(
                label = stringResource(R.string.alarms),
                icon = ImageVector.vectorResource(id = R.drawable.ic_alarm_black_24dp),
                route = Screens.Alarms.route,
            ),
        )

        if (WatchInfo.hasReminders) {
            baseItems.add(
                BottomNavigationItem(
                    label = stringResource(R.string.events),
                    icon = ImageVector.vectorResource(id = R.drawable.ic_event_black_24dp),
                    route = Screens.Events.route
                )
            )
        }

        if (BuildConfig.DEBUG && showDebug) {
            baseItems.add(
                BottomNavigationItem(
                    label = stringResource(R.string.title_debug),
                    icon = Icons.Filled.BugReport,
                    route = Screens.Debug.route
                )
            )
        }

        baseItems.add(
            BottomNavigationItem(
                label = stringResource(R.string.settings),
                icon = ImageVector.vectorResource(id = R.drawable.ic_settings),
                route = Screens.Settings.route
            )
        )

        return baseItems
    }
}
