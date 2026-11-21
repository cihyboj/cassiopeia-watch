package com.beamburst.casswatch.data.local

import org.avmedia.gshockapi.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class MergePendingEditsTest {

    private val baseline = Settings(timeFormat = "24h", timeAdjustment = true, adjustmentTimeMinutes = 30)

    @Test
    fun `edited sync minute is pushed`() {
        val fresh = baseline.copy(autoLight = true)
        val merged = mergePendingEdits(fresh, baseline, cached = baseline.copy(adjustmentTimeMinutes = 45))
        assertEquals(fresh.copy(adjustmentTimeMinutes = 45), merged)
    }

    @Test
    fun `fields without UI come from the watch`() {
        val fresh = baseline.copy(hourlyChime = true, DnD = true)
        val merged = mergePendingEdits(fresh, baseline, cached = baseline.copy(font = "Classic"))
        assertEquals(fresh.copy(font = "Classic"), merged)
    }

    @Test
    fun `legacy cache without baseline keeps the watch auto sync and chime`() {
        val fresh = baseline.copy(adjustmentTimeMinutes = 7, hourlyChime = true)
        val cached = baseline.copy(timeAdjustment = false, adjustmentTimeMinutes = 0, autoLight = true)
        val merged = mergePendingEdits(fresh, baseline = null, cached = cached)
        assertEquals(fresh.copy(autoLight = true), merged)
    }

    @Test
    fun `legacy cache never overrides the watch sync hour`() {
        val fresh = baseline.copy(adjustmentTimeHour = 9)
        val cached = baseline.copy(adjustmentTimeHour = 0, autoLight = true)
        val merged = mergePendingEdits(fresh, baseline = null, cached = cached)
        assertEquals(9, merged.adjustmentTimeHour)
    }
}
