package com.beamburst.casswatch.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHistoryScreen(
    onBack: () -> Unit,
    viewModel: SyncHistoryViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val expandedTs by viewModel.expandedTimestamp.collectAsState()
    val watchName by viewModel.watchName.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.sync_history_clear_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.sync_history_clear_confirm_body,
                        watchName.ifBlank { "this watch" }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clear(); showConfirm = false }) {
                    Text(stringResource(R.string.sync_history_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.sync_history_title))
                        if (watchName.isNotBlank()) {
                            Text(
                                text = watchName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_history_clear)) },
                                onClick = { showMenu = false; showConfirm = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.sync_history_no_entries),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.timestamp }) { entry ->
                    SyncEntryRow(
                        entry = entry,
                        expanded = expandedTs == entry.timestamp,
                        onClick = { viewModel.toggleExpand(entry.timestamp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncEntryRow(entry: SyncEntry, expanded: Boolean, onClick: () -> Unit) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val ts = TS_FMT.format(Instant.ofEpochMilli(entry.timestamp))
    val tag = entry.trigger.logTag()
    val kindsStr = entry.kinds.joinToString(" ") { k ->
        when (k) {
            SyncKind.TIME -> "time"
            SyncKind.ALARMS -> "alarms"
            SyncKind.EVENTS -> "events"
            SyncKind.HOURLY_SIGNAL -> "hourly"
            SyncKind.SETTINGS -> "settings"
            SyncKind.CONNECTION -> "conn"
            SyncKind.ACTION -> "action"
        }
    }
    val glyphColor = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val glyph = if (entry.success) "✓" else "✗"
    val errorSnippet = if (!entry.success) entry.errorMessage?.take(60) ?: "" else ""
    val line = "$ts  $tag  $kindsStr  $glyph${if (errorSnippet.isNotEmpty()) "  $errorSnippet" else ""}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = line,
            style = mono,
            color = if (entry.success) MaterialTheme.colorScheme.onSurface else glyphColor,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text("trigger = ${entry.trigger.description()}", style = mono)
                if (SyncKind.CONNECTION in entry.kinds) {
                    Text("connection = ${entry.details.connection ?: entry.trigger.description()}", style = mono)
                }
                entry.details.actions?.forEach { action ->
                    Text("action = $action", style = mono)
                }
                if (SyncKind.TIME in entry.kinds) {
                    val humanTs = entry.details.timeMs?.let { TS_FMT.format(Instant.ofEpochMilli(it)) } ?: "-"
                    Text("set time = $humanTs", style = mono)
                }
                if (SyncKind.ALARMS in entry.kinds) {
                    entry.details.alarmHashes?.forEach { h -> Text("  $h", style = mono) }
                        ?: Text("alarms = (none)", style = mono)
                }
                if (SyncKind.EVENTS in entry.kinds) {
                    Text("event count = ${entry.details.eventCount ?: 0}", style = mono)
                }
                if (SyncKind.HOURLY_SIGNAL in entry.kinds) {
                    val t = if (entry.details.hourlySignalTarget == true) "ON" else "OFF"
                    Text("hourly signal target = $t", style = mono)
                }
                if (!entry.success && !entry.errorMessage.isNullOrEmpty()) {
                    Text(entry.errorMessage, style = mono, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun SyncTrigger.logTag(): String =
    when (this) {
        SyncTrigger.AUTO -> "AUTO  "
        SyncTrigger.AUTO_ON_RECONNECT -> "RECON "
        SyncTrigger.WATCH_SHORT_PRESS -> "SHORT "
        SyncTrigger.WATCH_LONG_PRESS -> "LONG  "
        SyncTrigger.WATCH_NORMAL_CONNECTION -> "NCONN "
        SyncTrigger.WATCH_ALWAYS_CONNECTED -> "ACONN "
        SyncTrigger.CONNECTION_CONNECTED -> "CONN  "
        SyncTrigger.CONNECTION_DISCONNECTED -> "DISC  "
        SyncTrigger.CONNECTION_FAILED -> "FAIL  "
        else -> "MANUAL"
    }

private fun SyncTrigger.description(): String =
    when (this) {
        SyncTrigger.AUTO -> "Automatic sync"
        SyncTrigger.AUTO_ON_RECONNECT -> "Automatic sync on reconnect"
        SyncTrigger.WATCH_SHORT_PRESS -> "Short button press"
        SyncTrigger.WATCH_LONG_PRESS -> "Long button press / Find Phone"
        SyncTrigger.WATCH_NORMAL_CONNECTION -> "Normal connection button"
        SyncTrigger.WATCH_ALWAYS_CONNECTED -> "Always-connected button"
        SyncTrigger.MANUAL_SET_TIME -> "Manual time sync"
        SyncTrigger.MANUAL_SEND_ALARMS -> "Manual alarm sync"
        SyncTrigger.MANUAL_SEND_EVENTS -> "Manual event sync"
        SyncTrigger.MANUAL_SEND_SETTINGS -> "Manual settings sync"
        SyncTrigger.MANUAL_SETTINGS_SET_TIME -> "Settings set-time action"
        SyncTrigger.CONNECTION_CONNECTED -> "Connection setup complete"
        SyncTrigger.CONNECTION_DISCONNECTED -> "Disconnected"
        SyncTrigger.CONNECTION_FAILED -> "Connection failed"
    }
