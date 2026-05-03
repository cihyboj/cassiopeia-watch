package com.beamburst.casswatch

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.beamburst.casswatch.data.local.HourlySignalSettings
import com.beamburst.casswatch.services.NotificationProvider
import com.beamburst.casswatch.ui.alarms.applyHourlySignalToWatch
import com.beamburst.casswatch.ui.events.CalendarEvents
import com.beamburst.casswatch.ui.events.EventsModel
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.Utils
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import java.text.DateFormat
import java.time.Clock
import java.time.LocalDateTime
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: IGShockAPI,
    private val calendarEvents: CalendarEvents,
    private val notificationProvider: NotificationProvider,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        ProgressEvents.runEventActions(Utils.AppHashCode(), arrayOf(
            EventAction("ButtonPressedInfoReceived") {
                if (api.isAutoTimeStarted()) scope.launch { runChain() }
            },
        ))
    }

    suspend fun runChain() {
        val offset = LocalDataStorage.getFineTimeAdjustment(context)
        api.setTime(timeMs = System.currentTimeMillis() + offset)

        if (WatchInfo.hasReminders) {
            EventsModel.refresh(calendarEvents.getEventsFromCalendar())
            api.setEvents(EventsModel.events)
        }

        if (WatchInfo.chimeInSettings) {
            applyHourlySignalToWatch(api, HourlySignalSettings.load(context), LocalDateTime.now())
        }

        if (LocalDataStorage.getTimeAdjustmentNotification(context) && !WatchInfo.alwaysConnected) {
            postSyncNotification()
        }
    }

    private fun postSyncNotification() {
        val dateStr = DateFormat.getDateTimeInstance().format(Date(Clock.systemDefaultZone().millis()))
        val text = "Time set at $dateStr for ${WatchInfo.name} watch"
        notificationProvider.createNotification(
            NotificationProvider.NotificationContent(title = "Cassiopeia Watch", text = text)
        )
    }
}
