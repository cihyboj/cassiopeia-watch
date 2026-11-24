package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.AutoSyncSchedule
import com.beamburst.casswatch.data.local.HourlySignalSettings
import java.time.LocalDateTime

object HourlySignalEvaluator {
    fun shouldChimeAt(now: LocalDateTime, settings: HourlySignalSettings): Boolean {
        if (!settings.enabled) return false
        if (now.dayOfWeek !in settings.days) return false
        val endNorm = if (settings.endHour == 0) 24 else settings.endHour
        // The next full hour, moved back into the hour-0 cycle the window is stored in.
        val nextHour = AutoSyncSchedule.shiftedHour(now.hour + 1, -settings.syncShift)
        return nextHour in settings.startHour..endNorm
    }
}
