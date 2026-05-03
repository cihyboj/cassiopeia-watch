package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.HourlySignalSettings
import java.time.LocalDateTime

object HourlySignalEvaluator {
    fun shouldChimeAt(now: LocalDateTime, settings: HourlySignalSettings): Boolean {
        if (!settings.enabled) return false
        val endNorm = if (settings.endHour == 0) 24 else settings.endHour
        val nextHourBoundary = now.hour + 1
        return nextHourBoundary in settings.startHour until endNorm
    }
}
