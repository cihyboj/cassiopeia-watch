package com.beamburst.casswatch

import android.content.Context
import android.content.Intent
import com.beamburst.casswatch.data.local.BroadcastExtra
import com.beamburst.casswatch.data.local.ExtraType
import com.beamburst.casswatch.data.local.PressCardRepository
import com.beamburst.casswatch.ui.events.CalendarEvents
import com.beamburst.casswatch.ui.events.EventsModel
import com.beamburst.casswatch.utils.FlashlightHelper
import com.beamburst.casswatch.utils.PhoneFinder
import com.beamburst.casswatch.utils.Utils
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
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        ProgressEvents.runEventActions(Utils.AppHashCode(), arrayOf(
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
        if (cards.broadcast.enabled) sendBroadcast(BroadcastSpec.Short, cards.broadcast.extras)
        if (cards.flashlight.enabled) FlashlightHelper.toggle(context)
    }

    private fun handleLong() {
        val cards = cardRepository.longPress()
        if (cards.broadcast.enabled) sendBroadcast(BroadcastSpec.Long, cards.broadcast.extras)
        if (cards.findPhone.enabled) PhoneFinder.ring(context)
    }

    private suspend fun handleNormalConnection() {
        if (WatchInfo.hasReminders) {
            EventsModel.refresh(calendarEvents.getEventsFromCalendar())
            api.setEvents(EventsModel.events)
        }
    }

    private fun sendBroadcast(spec: BroadcastSpec, extras: List<BroadcastExtra>) {
        val intent = Intent(spec.action).apply {
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            extras.forEach { ex ->
                when (ex.type) {
                    ExtraType.STRING  -> putExtra(ex.key, ex.value)
                    ExtraType.INT     -> putExtra(ex.key, ex.value.toIntOrNull() ?: 0)
                    ExtraType.BOOLEAN -> putExtra(ex.key, ex.value.toBooleanStrictOrNull() ?: false)
                }
            }
        }
        context.sendBroadcast(intent)
    }
}
