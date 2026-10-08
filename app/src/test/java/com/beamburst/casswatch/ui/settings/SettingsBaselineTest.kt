package com.beamburst.casswatch.ui.settings

import com.beamburst.casswatch.data.local.WatchConnectionSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.avmedia.gshockapi.Settings
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsBaselineTest {
    private val address = "AA:BB:CC:DD:EE:01"

    @Test fun `no baseline disables controls and refuses whole object send`() {
        val state = SettingsState(address = address)
        assertFalse(state.hasBaseline)
        assertNull(state.settingsForSend(address))
    }

    @Test fun `initialized generation change reloads the baseline after opening Settings disconnected`() = runTest {
        val session = WatchConnectionSession()
        val selected = MutableStateFlow<String?>(address)
        var screen = SettingsState(address = address)
        var watchReads = 0
        val observation = backgroundScope.launch {
            settingsReloads(selected, session.initialized).collect { (_, ready) ->
                if (ready != null) {
                    watchReads++
                    screen = screen.withBaseline(address, Settings(hourlyChime = true, adjustmentTimeMinutes = 42))
                }
            }
        }
        runCurrent()
        assertFalse(screen.hasBaseline)
        session.begin(session.state.value, address)
        val first = session.established(address)!!
        runCurrent()
        assertEquals(0, watchReads)
        session.initializationCompleted(first)
        runCurrent()
        assertTrue(screen.hasBaseline)
        assertEquals(42, screen.settingsForSend(address)!!.adjustmentTimeMinutes)
        val second = session.renewed(first)!!
        session.initializationCompleted(second)
        runCurrent()
        assertEquals(2, watchReads)
        observation.cancel()
    }

    @Test fun `baseline arriving after connection enables controls and sends real settings`() {
        val initial = SettingsState(address = address)
        val loaded = Settings(timeFormat = "24h", hourlyChime = true, adjustmentTimeMinutes = 42)
        val refreshed = initial.withBaseline(address, loaded)
        assertTrue(refreshed.hasBaseline)
        assertEquals(loaded, refreshed.settingsForSend(address))
        assertNull(refreshed.settingsForSend("AA:BB:CC:DD:EE:02"))
        assertFalse(refreshed.draft === loaded)
    }

    @Test fun `refresh preserves pending edits and reloads untouched fields in the same control`() {
        val baseline = Settings(autoLight = false, lightDuration = "1.5s", hourlyChime = false)
        val pending = applySettingsEdit(baseline, SettingsViewModel.Light(), SettingsViewModel.Light(autoLight = true))
        val state = SettingsState(address = address, baseline = baseline, draft = pending)
        val refreshed = state.withBaseline(address, baseline.copy(lightDuration = "3s", hourlyChime = true))
        assertEquals(true, refreshed.draft!!.autoLight)
        assertEquals("3s", refreshed.draft.lightDuration)
        assertEquals(true, refreshed.draft.hourlyChime)
        assertEquals(false, refreshed.baseline!!.autoLight)
    }

    @Test fun `ordinary edits preserve hourly chime and every unedited API field`() {
        val baseline = Settings(hourlyChime = true, keyVibration = true, timeFormat = "24h",
            dateFormat = "DD:MM", language = "French", autoLight = true, lightDuration = "3s",
            powerSavingMode = true, adjustmentTimeMinutes = 42, fineAdjustment = 17, DnD = true, font = "Classic")
        val updated = applySettingsEdit(baseline, SettingsViewModel.OperationSound(vibrate = true),
            SettingsViewModel.OperationSound(sound = false, vibrate = true))
        assertEquals(baseline.copy(buttonTone = false), updated)
        assertTrue(baseline.buttonTone)
    }

    @Test fun `normalizing a displayed value does not overwrite an unedited watch field`() {
        val baseline = Settings(lightDuration = "2s", language = "Unknown", hourlyChime = true)
        val updated = applySettingsEdit(baseline, SettingsViewModel.Light(duration = SettingsViewModel.Light.LightDuration.THREE_SECONDS),
            SettingsViewModel.Light(autoLight = true, duration = SettingsViewModel.Light.LightDuration.THREE_SECONDS))
        assertEquals(baseline.copy(autoLight = true), updated)
    }

    @Test fun `an edit made while a send is in flight survives the send completing`() {
        val loaded = Settings(autoLight = false)
        val sent = loaded.copy(autoLight = true)
        val editedDuringSend = sent.copy(autoLight = false)
        // Send completion re-bases on what was sent, then merges what the watch now holds.
        val afterSend = SettingsState(address = address, baseline = sent.copy(), draft = editedDuringSend)
            .withBaseline(address, sent)
        assertFalse(afterSend.draft!!.autoLight)
        assertTrue(afterSend.baseline!!.autoLight)
        assertNotEquals(afterSend.baseline, afterSend.draft)
    }
}
