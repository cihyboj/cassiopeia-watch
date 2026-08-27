package com.beamburst.casswatch.ui.events

import org.avmedia.gshockapi.Event
import org.avmedia.gshockapi.EventDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object EventsModel {
    const val MAX_REMINDERS = 5
    private var _events = ArrayList<Event>()
    val events: ArrayList<Event>
        get() = _events

    fun refresh(newEvents: ArrayList<Event>) {
        _events = newEvents
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