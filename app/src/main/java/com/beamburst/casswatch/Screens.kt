package com.beamburst.casswatch

sealed class Screens(val route: String) {
    data object Time : Screens("Time")
    data object Alarms : Screens("Alarms")
    data object Events : Screens("Events")
    data object Debug : Screens("Debug")
    data object Settings : Screens("Settings") {
        const val routeWithAnchor = "Settings?anchor={anchor}"
        fun withAnchor(anchor: String) = "Settings?anchor=$anchor"
    }
    data object SyncHistory : Screens("SyncHistory")
}
