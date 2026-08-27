package com.beamburst.casswatch

import com.beamburst.casswatch.utils.AppScopes
import android.content.Context
import com.beamburst.casswatch.data.local.PressCardRepository
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.ui.events.CalendarEvents
import com.beamburst.casswatch.ui.events.EventsModel
import com.beamburst.casswatch.utils.PhoneFinder
import com.beamburst.casswatch.utils.SyncHistoryLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.IGShockAPI
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import javax.inject.Inject
import javax.inject.Singleton

enum class BroadcastSpec(val action: String) {
    Short("com.beamburst.casswatch.RUN_SHORT"),
    Long ("com.beamburst.casswatch.RUN_LONG"),
}

@Singleton
class WatchButtonDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: IGShockAPI,
    private val cardRepository: PressCardRepository,
    private val calendarEvents: CalendarEvents,
    private val autoSyncCoordinator: AutoSyncCoordinator,
    private val syncLogger: SyncHistoryLogger,
) {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "WatchButtonDispatcher"
    }

    private val scope = AppScopes.io

    init {
        ProgressEvents.runEventActions(EVENT_SUBSCRIBER_ID, arrayOf(
            EventAction("ButtonPressedInfoReceived") { scope.launch { onPress() } },
        ))
    }

    private suspend fun onPress() {
        when {
            api.isActionButtonPressed()              -> handleShort()
            api.isFindPhoneButtonPressed()           -> handleLong()
            api.isAutoTimeStarted()                  -> { /* AutoSyncCoordinator handles this */ }
            api.isNormalButtonPressed()              -> handleNormalConnection()
            api.isAlwaysConnectedConnectionPressed() -> autoSyncCoordinator.runChain(
                trigger = SyncTrigger.WATCH_ALWAYS_CONNECTED,
                actions = listOf("Always-connected button press"),
            )
        }
    }

    private suspend fun handleShort() {
        val cards = cardRepository.shortPress()
        val actions = mutableListOf("Short button press")
        if (cards.broadcast.enabled) {
            PressBroadcastEmitter.send(context, BroadcastSpec.Short, cards.broadcast.extras)
            actions += "Broadcast sent: ${BroadcastSpec.Short.action}"
            if (cards.broadcast.extras.isNotEmpty()) actions += "Broadcast extras: ${cards.broadcast.extras.size}"
        } else {
            actions += "Broadcast disabled"
        }
        recordWatchAction(SyncTrigger.WATCH_SHORT_PRESS, actions)
    }

    private suspend fun handleLong() {
        val cards = cardRepository.longPress()
        val actions = mutableListOf("Long button press / Find Phone function")
        if (cards.broadcast.enabled) {
            PressBroadcastEmitter.send(context, BroadcastSpec.Long, cards.broadcast.extras)
            actions += "Broadcast sent: ${BroadcastSpec.Long.action}"
            if (cards.broadcast.extras.isNotEmpty()) actions += "Broadcast extras: ${cards.broadcast.extras.size}"
        } else {
            actions += "Broadcast disabled"
        }
        if (cards.findPhone.enabled) {
            PhoneFinder.ring(
                context = context,
                volumePercent = cards.findPhone.volumePercent,
                melody = cards.findPhone.melody
            )
            actions += "Find my phone started: ${cards.findPhone.melody.name.lowercase()} at ${cards.findPhone.volumePercent}%"
        } else {
            actions += "Find my phone disabled"
        }
        recordWatchAction(SyncTrigger.WATCH_LONG_PRESS, actions)
    }

    private suspend fun handleNormalConnection() {
        val kinds = mutableSetOf(SyncKind.CONNECTION)
        val actions = mutableListOf("Normal connection button press")
        var success = true
        var error: String? = null
        if (WatchInfo.hasReminders) {
            runCatching {
                EventsModel.refresh(calendarEvents.getEventsFromCalendar())
                api.setEvents(EventsModel.events)
                kinds += SyncKind.EVENTS
                actions += "Events synchronized: ${EventsModel.events.size}"
            }.onFailure {
                success = false
                error = it.message
            }
        } else {
            actions += "No reminder support for this watch"
        }
        syncLogger.record(
            trigger = SyncTrigger.WATCH_NORMAL_CONNECTION,
            kinds = kinds,
            success = success,
            errorMessage = error,
            details = SyncDetails(
                alarmHashes = null,
                eventCount = if (SyncKind.EVENTS in kinds) EventsModel.events.size else null,
                hourlySignalTarget = null,
                timeMs = null,
                connection = "Normal connection button press",
                actions = actions,
            ),
        )
    }

    private suspend fun recordWatchAction(trigger: SyncTrigger, actions: List<String>) {
        syncLogger.record(
            trigger = trigger,
            kinds = setOf(SyncKind.ACTION),
            success = true,
            details = SyncDetails.Empty.copy(actions = actions),
        )
    }
}
