/*
 * Created by Ivo Zivkov (izivkov@gmail.com) on 2022-04-03, 6:13 p.m.
 * Copyright (c) 2022 . All rights reserved.
 * Last modified 2022-04-03, 6:13 p.m.
 */
// Modified for Cassiopeia Watch by the Cassiopeia Watch contributors.

package com.beamburst.casswatch.ui.events

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.beamburst.casswatch.utils.AppScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import org.avmedia.gshockapi.Event
import org.avmedia.gshockapi.EventDate
import org.avmedia.gshockapi.ProgressEvents
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarEvents @Inject constructor(
    private val rRuleValues: RRuleValues,
    @param:ApplicationContext private val appContext: Context // Inject application context
) {
    private val calendarObserver = CalendarObserver()

    fun getEventsFromCalendar(): ArrayList<Event> {
        return getEvents(appContext)
    }

    @SuppressLint("Range")
    private fun getEvents(context: Context): ArrayList<Event> {
        val events: ArrayList<Event> = ArrayList()
        val cr: ContentResolver = context.contentResolver

        val uri = buildCalendarUri()
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_OWNER.toString())
        val cursor = cr.query(
            uri,
            getProjection(),
            buildSelection(),
            selectionArgs,
            "${CalendarContract.Instances.BEGIN} ASC"
        )

        calendarObserver.register(cr, uri)
        cursor?.use {
            processCursor(it, events)
        }

        return events
    }

    private fun buildCalendarUri(): Uri {
        val startMillis = Calendar.getInstance().timeInMillis
        val endMillis = Calendar.getInstance()
            .apply { add(Calendar.YEAR, 2) }.timeInMillis // limit to 2 years in the future.

        val builder: Uri.Builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMillis)
        ContentUris.appendId(builder, endMillis)

        return builder.build()
    }

    private fun getProjection(): Array<String> {
        return arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.DTSTART
        )
    }

    private fun buildSelection(): String {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.MILLISECOND, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.HOUR_OF_DAY, 0)
        }

        return """
            (${CalendarContract.Events.DTEND} >= ${calendar.timeInMillis}
            or ${CalendarContract.Events.RRULE} IS NOT NULL)
            and (${CalendarContract.Events.CALENDAR_ACCESS_LEVEL} = ?
            or ${CalendarContract.Events.HAS_ALARM} = "1")
        """.trimIndent()
    }

    private fun processCursor(cursor: Cursor, events: ArrayList<Event>) {
        val seenEventIds = mutableSetOf<Long>()
        cursor.moveToPosition(-1) // move before the first event

        while (cursor.moveToNext()) {
            val eventId =
                cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID))
            val eventStart =
                cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN))

            if (eventId !in seenEventIds && eventStart > System.currentTimeMillis()) {
                seenEventIds.add(eventId)
                // One malformed calendar row (null DTSTART, unparsable RRULE, ...) must not
                // abort the whole sync.
                runCatching {
                    processEvent(cursor, events)
                }.onFailure { Timber.w(it, "Skipping malformed calendar event %d", eventId) }
            }
        }
    }

    // @SuppressLint("Range")
    private fun processEvent(cursor: Cursor, events: ArrayList<Event>) {

        val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
        // TITLE can be NULL in the calendar provider (untitled events are common).
        val title: String = cursor.getString(titleIndex) ?: "(No title)"

        val dateStartIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
        val dateStart: String? = cursor.getString(dateStartIndex)

        val rruleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.RRULE)
        val rrule: String? = if (rruleIndex >= 0) cursor.getString(rruleIndex) else null

        val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
        val allDay: String? = if (allDayIndex >= 0) cursor.getString(allDayIndex) else null

        val zone = if (allDay == "1") ZoneId.of("UTC") else ZoneId.systemDefault()

        val startMillis = dateStart?.toLongOrNull() ?: return // no usable start time
        val startDate = EventsModel.createEventDate(startMillis, zone)
        var endDate = startDate

        val (localEndDate, incompatible, daysOfWeek, repeatPeriod) = rRuleValues.getValues(
            rrule,
            startDate,
            zone
        )

        localEndDate?.let {
            endDate = EventDate(it.year, it.month, it.dayOfMonth)
        }

        val end = LocalDate.of(endDate.year, endDate.month, endDate.day)
        if (!startDate.equals(endDate) && end.isBefore(LocalDate.now())) return // skip expired events

        val enabled = events.size < EventsModel.MAX_REMINDERS
        events.add(
            Event(
                title,
                startDate,
                endDate,
                repeatPeriod,
                daysOfWeek,
                enabled,
                incompatible
            )
        )
    }

    inner class CalendarObserver {
        private var registered = false

        private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // The query is too heavy for the main thread, and a provider hiccup here
                // must not crash the app.
                AppScopes.io.launch {
                    runCatching {
                        ProgressEvents.onNext("CalendarUpdated", getEvents(appContext))
                    }.onFailure { Timber.w(it, "Calendar change refresh failed") }
                }
            }
        }

        fun register(cr: ContentResolver, uri: Uri) {
            if (!registered) {
                cr.registerContentObserver(uri, true, calendarObserver)
                registered = true
            }
        }
    }
}