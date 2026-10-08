package com.beamburst.casswatch.pairing

import android.content.Context
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.ui.common.AppSnackbar
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

/**
 * The library launches writes without a delivery acknowledgment. Yielding and checking
 * before committing success catches app-observed session changes, but cannot fence its
 * detached transport queue or prove BLE delivery.
 */
class WatchWriteGuard internal constructor(
    private val selectedAddress: () -> String?,
    private val session: WatchConnectionSession,
    private val coordinator: WatchConnectCoordinator,
    private val refused: () -> Unit,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        selector: CurrentWatchSelector,
        session: WatchConnectionSession,
        coordinator: WatchConnectCoordinator,
    ) : this(
        selector::currentAddress,
        session,
        coordinator,
        { AppSnackbar(context.getString(R.string.selected_watch_not_connected)) },
    )

    fun capture(): WatchConnectionSession.Ready? = session.matching(selectedAddress())?.takeIf(session::isInitialized).also {
        if (it == null) refused()
    }

    suspend fun run(
        expected: WatchConnectionSession.Ready,
        block: suspend (check: () -> Unit) -> Unit,
    ) {
        try {
            session.awaitInitialized(expected)
            coordinator.withSession(expected, selectedAddress, block)
        } catch (_: WatchConnectionChangedException) {
            refused()
        }
    }

    /** Automatic screen refreshes quietly abandon obsolete reads. */
    suspend fun readApi(
        expected: WatchConnectionSession.Ready,
        api: org.avmedia.gshockapi.IGShockAPI,
        block: suspend (api: org.avmedia.gshockapi.IGShockAPI, confirm: suspend () -> Unit) -> Unit,
    ) {
        try {
            session.awaitInitialized(expected)
            coordinator.withSession(expected, selectedAddress) { check ->
                block(api.guardWrites(check)) { confirmWatchWrite(check) }
            }
        } catch (_: WatchConnectionChangedException) {
            // Selection or generation changed; the next refresh owns the UI.
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (session.isCurrent(expected)) throw cancelled
        }
    }

    /** Alarm handlers can use this API for bounded reads and fenced helper writes. */
    suspend fun runApi(
        expected: WatchConnectionSession.Ready,
        api: org.avmedia.gshockapi.IGShockAPI,
        block: suspend (api: org.avmedia.gshockapi.IGShockAPI, confirm: suspend () -> Unit) -> Unit,
    ) = run(expected) { check ->
        block(api.guardWrites(check)) { confirmWatchWrite(check) }
    }
}

class WatchConnectionChangedException : IllegalStateException("Watch connection changed")
