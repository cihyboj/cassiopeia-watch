package com.beamburst.casswatch.pairing

import org.avmedia.gshockapi.Alarm
import org.avmedia.gshockapi.AppNotification
import org.avmedia.gshockapi.Event
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.Settings

/** Recheck at the actual write, including helpers that suspend between a read and a write. */
fun IGShockAPI.guardWrites(checkSession: () -> Unit): IGShockAPI {
    val delegate = this
    return object : IGShockAPI by delegate {
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
            delegate.setTime(timeZone, timeMs)
            checkSession()
        }

        override suspend fun setScratchpadData(data: ByteArray, startIndex: Int) {
            checkSession()
            delegate.setScratchpadData(data, startIndex)
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
