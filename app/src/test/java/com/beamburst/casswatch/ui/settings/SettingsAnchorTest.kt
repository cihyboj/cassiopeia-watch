package com.beamburst.casswatch.ui.settings

import com.beamburst.casswatch.Screens
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsAnchorTest {
    @Test
    fun `sync anchor builds settings route`() {
        assertEquals("sync", SettingsAnchor.Sync.routeValue)
        assertEquals("Settings?anchor=sync", Screens.Settings.withAnchor(SettingsAnchor.Sync.routeValue))
    }
}
