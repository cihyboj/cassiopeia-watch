package com.beamburst.casswatch.pairing

import org.avmedia.gshockapi.Alarm
import org.avmedia.gshockapi.AppNotification
import org.avmedia.gshockapi.Event
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.Settings
import org.avmedia.gshockapi.io.IO
import org.avmedia.gshockapi.io.TimeAdjustmentInfo

/** Recheck at the actual write, including helpers that suspend between a read and a write. */
fun IGShockAPI.guardWrites(
    checkSession: () -> Unit,
    responseTimeoutMs: Long = WATCH_RESPONSE_TIMEOUT_MS,
): IGShockAPI {
    val delegate = this
    return object : IGShockAPI by delegate {
        override suspend fun init(): Boolean = response(WATCH_INITIALIZATION_TIMEOUT_MS) { delegate.init() }
        override suspend fun getPressedButton(): IO.WatchButton = response { delegate.getPressedButton() }
        override suspend fun getWatchName(): String = response { delegate.getWatchName() }
        override suspend fun getError(): String = response { delegate.getError() }
        override suspend fun getDSTWatchState(state: IO.DstState): String = response { delegate.getDSTWatchState(state) }
        override suspend fun getDSTForWorldCities(cityNumber: Int): String = response { delegate.getDSTForWorldCities(cityNumber) }
        override suspend fun getWorldCities(cityNumber: Int): String = response { delegate.getWorldCities(cityNumber) }
        override suspend fun getHomeTime(): String = response { delegate.getHomeTime() }
        override suspend fun getBatteryLevel(): Int = response { delegate.getBatteryLevel() }
        override suspend fun getWatchTemperature(): Int = response { delegate.getWatchTemperature() }
        override suspend fun getTimer(): Int = response { delegate.getTimer() }
        override suspend fun getAppInfo(): String = response { delegate.getAppInfo() }
        override suspend fun getScratchpadData(index: Int, length: Int): ByteArray = response { delegate.getScratchpadData(index, length) }
        override suspend fun primeTimeCache() {
            // Preload each public read separately. TimeIO then uses these cached values
            // while preserving its own writes and model-specific initialization.
            val dstStates = arrayOf(IO.DstState.ZERO, IO.DstState.TWO, IO.DstState.FOUR)
            for (index in 0 until WatchInfo.dstCount) getDSTWatchState(dstStates[index])
            for (city in 0 until WatchInfo.worldCitiesCount) getDSTForWorldCities(city)
            if (WatchInfo.hasWorldCities) {
                for (city in 1 until WatchInfo.worldCitiesCount) getWorldCities(city)
            }
            response(WATCH_TIME_TIMEOUT_MS) { delegate.primeTimeCache() }
        }
        override suspend fun getAlarms(): ArrayList<Alarm> = response(WATCH_ALARMS_TIMEOUT_MS) { delegate.getAlarms() }
        override suspend fun getEventsFromWatch(): ArrayList<Event> =
            ArrayList<Event>().apply {
                for (number in 1..5) add(getEventFromWatch(number))
            }
        override suspend fun getEventFromWatch(eventNumber: Int): Event = response(WATCH_EVENT_TIMEOUT_MS) { delegate.getEventFromWatch(eventNumber) }
        override suspend fun getSettings(): Settings = response(WATCH_SETTINGS_TIMEOUT_MS) {
            getBasicSettings().also { settings ->
                val adjustment = getTimeAdjustment()
                settings.timeAdjustment = adjustment.isTimeAdjustmentSet
                settings.adjustmentTimeMinutes = adjustment.adjustmentTimeMinutes
                settings.adjustmentTimeHour = adjustment.adjustmentTimeHour
            }
        }
        override suspend fun getBasicSettings(): Settings = response { delegate.getBasicSettings() }
        override suspend fun getTimeAdjustment(): TimeAdjustmentInfo = response { delegate.getTimeAdjustment() }

        private suspend fun <T> response(
            budgetMs: Long = WATCH_RESPONSE_TIMEOUT_MS,
            block: suspend () -> T,
        ): T {
            checkSession()
            return awaitWatchResponse(responseTimeoutMs * (budgetMs / WATCH_RESPONSE_TIMEOUT_MS)) { block() }.also { confirmWatchWrite(checkSession) }
        }

        override fun clearEvents() {
            checkSession()
            delegate.clearEvents()
            checkSession()
        }

        override fun setSettings(settings: Settings) {
            checkSession()
            delegate.setSettings(settings)
            checkSession()
        }

        override fun setAlarms(alarms: ArrayList<Alarm>) {
            checkSession()
            delegate.setAlarms(alarms)
            checkSession()
        }

        override fun setEvents(events: ArrayList<Event>) {
            checkSession()
            delegate.setEvents(events)
            checkSession()
        }

        override fun setTimer(timerValue: Int) {
            checkSession()
            delegate.setTimer(timerValue)
            checkSession()
        }

        override suspend fun setTime(timeZone: String, timeMs: Long?) {
            checkSession()
            response(WATCH_TIME_TIMEOUT_MS) { delegate.setTime(timeZone, timeMs) }
            checkSession()
        }

        override suspend fun setScratchpadData(data: ByteArray, startIndex: Int) {
            checkSession()
            response { delegate.setScratchpadData(data, startIndex) }
            checkSession()
        }

        override fun sendAppNotification(notification: AppNotification) {
            checkSession()
            delegate.sendAppNotification(notification)
            checkSession()
        }

        override fun sendMessage(message: String) {
            checkSession()
            delegate.sendMessage(message)
            checkSession()
        }

        override fun resetHand() {
            checkSession()
            delegate.resetHand()
            checkSession()
        }
    }
}
