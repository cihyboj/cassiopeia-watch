package com.beamburst.casswatch.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import com.beamburst.casswatch.theme.Spacing
import com.beamburst.casswatch.ui.common.AppText

@Composable
fun WatchRow(
    watch: PairedWatch,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val background =
        if (watch.isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        color = background,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (watch.isSelected) Spacing.xs else Spacing.xxs,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
            ) {
                AppText(
                    text = watch.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AppText(
                    text = watch.subtitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (watch.state == ConnectionState.CONNECTED) {
                OnlinePill()
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                WatchActionsMenu(
                    expanded = showMenu,
                    state = watch.state,
                    onDismiss = { showMenu = false },
                    onConnect = {
                        showMenu = false
                        onConnect()
                    },
                    onDisconnect = {
                        showMenu = false
                        onDisconnect()
                    },
                    onForget = {
                        showMenu = false
                        onForget()
                    }
                )
            }
        }
    }
}

@Composable
private fun PairedWatch.subtitle(): String {
    val context = LocalContext.current
    return formatWatchRowSubtitle(
        state = state,
        snapshot = snapshot,
        hasSeenWatch = hasSeenWatch,
        homeTimeLabel = stringResource(R.string.home_time),
        connectedLabel = stringResource(R.string.status_connected),
        connectingLabel = stringResource(R.string.connecting),
        connectionErrorLabel = stringResource(R.string.connection_error),
        disconnectedLabel = stringResource(R.string.status_disconnected),
        connectToReadWatchLabel = stringResource(R.string.connect_to_read_watch),
        cachedPrefix = { context.getString(R.string.cached_prefix, it) },
    )
}

@Composable
private fun OnlinePill() {
    val (containerColor, contentColor) =
        if (isSystemInDarkTheme()) {
            Color(0xFF1B5E20) to Color(0xFFC8E6C9)
        } else {
            Color(0xFFE8F5E9) to Color(0xFF1B5E20)
        }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        AppText(
            text = stringResource(R.string.status_online),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        )
    }
}

@Composable
private fun WatchActionsMenu(
    expanded: Boolean,
    state: ConnectionState,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        when (state) {
            ConnectionState.CONNECTED ->
                DropdownMenuItem(
                    text = { AppText(stringResource(R.string.disconnect)) },
                    onClick = onDisconnect
                )
            ConnectionState.CONNECTING ->
                DropdownMenuItem(
                    text = { AppText(stringResource(R.string.cancel)) },
                    onClick = onDisconnect
                )
            ConnectionState.DISCONNECTED,
            ConnectionState.ERROR ->
                DropdownMenuItem(
                    text = { AppText(stringResource(R.string.connect)) },
                    onClick = onConnect
                )
        }

        DropdownMenuItem(
            text = { AppText(stringResource(R.string.forget)) },
            onClick = onForget
        )
    }
}
