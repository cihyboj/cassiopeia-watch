package com.beamburst.casswatch.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fired from the same call sites as connect/disconnect snackbars ([MainEventHandler]) so the Time
 * tab can mirror that UX without depending only on [ProgressEvents] ordering or [api.isConnected]
 * timing.
 */
@Singleton
class WatchSessionUiBridge @Inject constructor() {

    private val _linkEvents = MutableSharedFlow<SessionLinkEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val linkEvents: SharedFlow<SessionLinkEvent> = _linkEvents.asSharedFlow()

    fun emitDisconnected() {
        _linkEvents.tryEmit(SessionLinkEvent.Disconnected)
    }

    fun emitConnected() {
        _linkEvents.tryEmit(SessionLinkEvent.Connected)
    }
}

sealed interface SessionLinkEvent {
    data object Disconnected : SessionLinkEvent
    data object Connected : SessionLinkEvent
}
