package com.beamburst.casswatch.ui.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.utils.LocalDataStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.avmedia.gshockapi.WatchInfo

data class WatchStatusBadgeState(
    val connected: Boolean = false,
    val label: String = "",
)

@HiltViewModel
class WatchStatusBadgeViewModel @Inject constructor(
    private val repository: GShockRepository,
    private val selector: CurrentWatchSelector,
    private val tracker: WatchConnectionTracker,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {
    val state: StateFlow<WatchStatusBadgeState> =
        combine(selector.selected, tracker.states) { selected, states ->
            val connected = selected != null &&
                (states.stateFor(selected) == ConnectionState.CONNECTED ||
                    states.stateFor(selected) == null && repository.isConnected())
            WatchStatusBadgeState(
                connected = connected,
                label = if (connected) resolveWatchName(selected) else appContext.getString(R.string.status_disconnected),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = WatchStatusBadgeState(
                connected = false,
                label = appContext.getString(R.string.status_disconnected),
            ),
        )

    private fun resolveWatchName(address: String?): String {
        val liveName = WatchInfo.name
            ?.removePrefix("CASIO")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (liveName != null) return liveName
        if (!address.isNullOrBlank()) {
            LocalDataStorage.getDeviceName(appContext, address)
                ?.removePrefix("CASIO")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return appContext.getString(R.string.no_watch)
    }

    private fun Map<String, ConnectionState>.stateFor(address: String): ConnectionState? =
        this[address] ?: entries.firstOrNull { it.key.equals(address, ignoreCase = true) }?.value
}

@Composable
fun WatchStatusBadge(
    modifier: Modifier = Modifier,
    viewModel: WatchStatusBadgeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    WatchStatusBadgeContent(state = state, modifier = modifier)
}

@Composable
fun WatchStatusBadgeContent(
    state: WatchStatusBadgeState,
    modifier: Modifier = Modifier,
) {
    val dotColor = if (state.connected) Color(0xFF42D36B) else Color(0xFFFF5A5F)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier
                .widthIn(max = 168.dp)
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(dotColor.copy(alpha = 0.22f), CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(dotColor, CircleShape),
                )
            }
            AppText(
                text = state.label.ifBlank { stringResource(R.string.status_disconnected) },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
