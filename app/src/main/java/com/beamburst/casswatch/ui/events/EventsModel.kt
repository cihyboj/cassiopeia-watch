package com.beamburst.casswatch.ui.events

import org.avmedia.gshockapi.Event
import org.avmedia.gshockapi.EventDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object EventsModel {
    const val MAX_REMINDERS = 5

    // Written by EventViewModel, AutoSyncCoordinator, WatchButtonDispatcher, and the
    // calendar ContentObserver from different threads; read while iterating in
    // api.setEvents(). Synchronize and hand out copies so a concurrent refresh cannot
    // corrupt an in-flight send.
    private var _events = ArrayList<Event>()

    val events: ArrayList<Event>
        @Synchronized
        get() = ArrayList(_events)

    @Synchronized
    fun refresh(newEvents: List<Event>) {
        _events = ArrayList(newEvents)
    }

    fun createEventDate(timeMs: Long, zone: ZoneId): EventDate {
        val start: LocalDate = Instant.ofEpochMilli(timeMs)
            .atZone(zone)
            .toLocalDate()

        return EventDate(
            start.year,
            start.month,
            start.dayOfMonth
        )
    }
}