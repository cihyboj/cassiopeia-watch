package com.beamburst.casswatch

import android.content.Context
import com.beamburst.casswatch.data.local.PressCardRepository
import com.beamburst.casswatch.ui.events.CalendarEvents
import com.beamburst.casswatch.ui.events.EventsModel
import com.beamburst.casswatch.utils.PhoneFinder
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
) {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "WatchButtonDispatcher"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

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
            api.isAlwaysConnectedConnectionPressed() -> autoSyncCoordinator.runChain()
        }
    }

    private fun handleShort() {
        val cards = cardRepository.shortPress()
        if (cards.broadcast.enabled) PressBroadcastEmitter.send(context, BroadcastSpec.Short, cards.broadcast.extras)
    }

    private fun handleLong() {
        val cards = cardRepository.longPress()
        if (cards.broadcast.enabled) PressBroadcastEmitter.send(context, BroadcastSpec.Long, cards.broadcast.extras)
        if (cards.findPhone.enabled) {
            PhoneFinder.ring(
                context = context,
                volumePercent = cards.findPhone.volumePercent,
                melody = cards.findPhone.melody
            )
        }
    }

    private suspend fun handleNormalConnection() {
        if (WatchInfo.hasReminders) {
            EventsModel.refresh(calendarEvents.getEventsFromCalendar())
            api.setEvents(EventsModel.events)
        }
    }

}
