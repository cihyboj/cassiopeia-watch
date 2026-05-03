package com.beamburst.casswatch.ui.events

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.utils.SyncHistoryLogger
import com.beamburst.casswatch.ui.common.AppSnackbar
import org.avmedia.gshockapi.Event
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import timber.log.Timber
import java.text.Normalizer
import java.util.regex.Pattern
import javax.inject.Inject

@HiltViewModel
class EventViewModel @Inject constructor(
    private val api: GShockRepository,
    private val calendarEvents: CalendarEvents,
    @param:ApplicationContext private val appContext: Context,
    private val syncLogger: SyncHistoryLogger,
) : ViewModel() {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "EventViewModel.listenForUpdateRequest"
    }

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        listenForUpdateRequest()
    }

    fun loadEvents() {
        viewModelScope.launch {
            runCatching {
                val loadedEvents = calendarEvents.getEventsFromCalendar()
                _events.value = loadedEvents
                EventsModel.refresh(loadedEvents)
            }.onFailure {
                AppSnackbar("Error: ${it.message}")
            }
        }
    }

    fun toggleEvents(index: Int, isEnabled: Boolean) {
        _events.value = _events.value.toMutableList().apply {
            this[index] = this[index].copy(enabled = isEnabled)
        }
    }

    private fun listenForUpdateRequest() {
        val eventActions = arrayOf(
            EventAction("CalendarUpdated") {
                Timber.d("CalendarUpdated, events: ${EventsModel.events.size}")
                @Suppress("UNCHECKED_CAST")
                _events.value = ProgressEvents.getPayload("CalendarUpdated") as List<Event>
            },
        )

        ProgressEvents.runEventActions(
            EVENT_SUBSCRIBER_ID,
            eventActions
        )
    }

    override fun onCleared() {
        ProgressEvents.subscriber.stop(EVENT_SUBSCRIBER_ID)
        super.onCleared()
    }

    private fun String.sanitizeEventTitle(): String {
        fun String.filterAllowedCharacters(): String {
            val allowedSymbols =
                " !\"#\\\$%&'()*+,-./:;<=>?@[\\]^_`{|}" // Not supported on the watch: "~。「」、・。¥±♪⟪⟫♦▶◀"
            val regex = "[^A-Za-z0-9${Regex.escape(allowedSymbols)}]".toRegex()
            return this.replace(regex, "*")
        }

        fun String.removeEmojis(): String {
            return this.replace(Regex("[\\p{So}\\p{Cn}]"), "")
        }

        fun String.removeAccents(): String {
            val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
            return Pattern.compile("\\p{InCombiningDiacriticalMarks}+").matcher(normalized)
                .replaceAll("")
        }

        return this.removeEmojis().removeAccents().filterAllowedCharacters().trim()
    }

    fun sendEventsToWatch() {
        viewModelScope.launch {
            val sanitizedEvents = _events.value.map { event ->
                event.copy(title = event.title.sanitizeEventTitle())
            }
            val result = runCatching {
                api.setEvents(ArrayList(sanitizedEvents))
            }
            syncLogger.record(
                trigger = SyncTrigger.MANUAL_SEND_EVENTS,
                kinds = setOf(SyncKind.EVENTS),
                success = result.isSuccess,
                errorMessage = result.exceptionOrNull()?.message,
                details = SyncDetails(
                    alarmHashes = null,
                    eventCount = sanitizedEvents.size,
                    hourlySignalTarget = null,
                    timeMs = null,
                ),
            )
            if (result.isSuccess) {
                AppSnackbar(appContext.getString(R.string.reminders_sent_to_watch))
            } else {
                AppSnackbar("Error: ${result.exceptionOrNull()?.message ?: ""}")
            }
        }
    }

    /**
     * Represents one-time UI events that should be handled by the UI layer.
     */
    sealed class UiEvent {
        data class ShowSnackbar(val message: String) : UiEvent()
    }
}
