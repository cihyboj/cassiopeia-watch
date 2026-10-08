package com.beamburst.casswatch.pairing

import android.content.Context
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.ui.common.AppSnackbar
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

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

    fun capture(): WatchConnectionSession.Ready? = session.matching(selectedAddress()).also {
        if (it == null) refused()
    }

    suspend fun run(
        expected: WatchConnectionSession.Ready,
        block: suspend (check: () -> Unit) -> Unit,
    ) {
        try {
            coordinator.withSession(expected, selectedAddress, block)
        } catch (_: WatchConnectionChangedException) {
            refused()
        }
    }

}

class WatchConnectionChangedException : IllegalStateException("Watch connection changed")
