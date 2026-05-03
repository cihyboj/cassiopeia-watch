package com.beamburst.casswatch.ui.alarms

import com.beamburst.casswatch.data.local.HourlySignalSettings
import org.avmedia.gshockapi.IGShockAPI
import java.time.LocalDateTime

suspend fun applyHourlySignalToWatch(
    api: IGShockAPI,
    settings: HourlySignalSettings,
    now: LocalDateTime,
) {
    val target = HourlySignalEvaluator.shouldChimeAt(now, settings)
    val current = api.getSettings()
    if (current.hourlyChime != target) {
        api.setSettings(current.copy(hourlyChime = target))
    }
}
