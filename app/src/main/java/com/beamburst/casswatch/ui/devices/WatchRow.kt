package com.beamburst.casswatch.ui.devices

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
            ConnectionDot(state = watch.state)

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
private fun PairedWatch.subtitle(): String =
    when (state) {
        ConnectionState.CONNECTED -> listOfNotNull(
            snapshot?.battery?.let { "$it%" },
            snapshot?.temperature?.let { "$it°C" },
            snapshot?.homeTime?.let { "${stringResource(R.string.home_time)} $it" },
        ).takeIf { it.isNotEmpty() }?.joinToString(" • ")
            ?: stringResource(R.string.status_connected)
        ConnectionState.CONNECTING -> stringResource(R.string.connecting)
        ConnectionState.ERROR -> stringResource(R.string.connection_error)
        ConnectionState.DISCONNECTED -> {
            val cached = listOfNotNull(
                snapshot?.battery?.let { "$it%" },
                snapshot?.temperature?.let { "$it°C" },
            )
            if (cached.isNotEmpty()) {
                stringResource(R.string.cached_prefix, cached.joinToString(" • "))
            } else {
                stringResource(R.string.not_yet_synced)
            }
        }
    }

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Canvas(modifier = Modifier.size(Spacing.md)) {
        drawCircle(color = color)
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
