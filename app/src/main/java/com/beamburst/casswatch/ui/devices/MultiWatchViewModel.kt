package com.beamburst.casswatch.ui.devices

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.PerWatchCacheCleaner
import com.beamburst.casswatch.data.local.SyncHistoryStore
import com.beamburst.casswatch.data.local.SyncDetails
import com.beamburst.casswatch.data.local.SyncKind
import com.beamburst.casswatch.data.local.SyncTrigger
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.local.WatchConnectionTracker.ConnectionState
import com.beamburst.casswatch.data.local.WatchSnapshot
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.pairing.DeviceAssociationManager
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.SyncHistoryLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class PairedWatch(
    val address: String,
    val name: String,
    val state: ConnectionState,
    val snapshot: WatchSnapshot?,
    val hasSeenWatch: Boolean,
    val isSelected: Boolean,
)

@HiltViewModel
class MultiWatchViewModel @Inject constructor(
    private val repository: GShockRepository,
    private val selector: CurrentWatchSelector,
    private val tracker: WatchConnectionTracker,
    private val syncHistoryStore: SyncHistoryStore,
    private val syncHistoryLogger: SyncHistoryLogger,
    private val deviceAssociationManager: DeviceAssociationManager,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0L)

    val paired: StateFlow<List<PairedWatch>> =
        combine(selector.selected, tracker.states, refreshTick) { selected, states, _ ->
            loadPairedWatches(selected, states)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshTick.value = System.currentTimeMillis()
        }
    }

    fun select(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val selectedWatch = paired.value.firstOrNull { it.address.equals(address, ignoreCase = true) }
            if (selectedWatch?.state != ConnectionState.CONNECTED && tracker.states.value[address] == null) {
                tracker.mark(address, ConnectionState.DISCONNECTED)
            }
            selector.select(address)
            refresh()
        }
    }

    fun connect(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            selector.select(address)
            val previouslySelected = paired.value.firstOrNull {
                it.state == ConnectionState.CONNECTED && !it.address.equals(address, ignoreCase = true)
            }?.address

            runCatching {
                previouslySelected?.let { tracker.mark(it, ConnectionState.DISCONNECTED) }
                if (repository.isConnected()) repository.disconnect()
                tracker.mark(address, ConnectionState.CONNECTING)
                repository.waitForConnection(address)
                tracker.mark(address, ConnectionState.CONNECTED)
                syncHistoryLogger.record(
                    trigger = SyncTrigger.CONNECTION_CONNECTED,
                    kinds = setOf(SyncKind.CONNECTION),
                    success = true,
                    details = SyncDetails.Empty.copy(connection = "Started from watch row Connect menu"),
                )
            }.onFailure {
                Timber.e(it, "Failed to connect watch $address")
                tracker.mark(address, ConnectionState.ERROR)
                syncHistoryLogger.record(
                    trigger = SyncTrigger.CONNECTION_FAILED,
                    kinds = setOf(SyncKind.CONNECTION),
                    success = false,
                    errorMessage = it.message,
                    details = SyncDetails.Empty.copy(connection = "Started from watch row Connect menu"),
                )
            }
            refresh()
        }
    }

    fun disconnect(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.disconnect()
                tracker.mark(address, ConnectionState.DISCONNECTED)
                syncHistoryLogger.record(
                    trigger = SyncTrigger.CONNECTION_DISCONNECTED,
                    kinds = setOf(SyncKind.CONNECTION),
                    success = true,
                    details = SyncDetails.Empty.copy(connection = "Started from watch row Disconnect menu"),
                )
            }.onFailure {
                Timber.e(it, "Failed to disconnect watch $address")
                tracker.mark(address, ConnectionState.ERROR)
                syncHistoryLogger.record(
                    trigger = SyncTrigger.CONNECTION_FAILED,
                    kinds = setOf(SyncKind.CONNECTION),
                    success = false,
                    errorMessage = it.message,
                    details = SyncDetails.Empty.copy(connection = "Started from watch row Disconnect menu"),
                )
            }
            refresh()
        }
    }

    fun forget(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val remaining = LocalDataStorage.getDeviceAddresses(appContext)
                .filterNot { it.equals(address, ignoreCase = true) }

            if (selector.currentAddress()?.equals(address, ignoreCase = true) == true) {
                remaining.firstOrNull()?.let { selector.select(it) } ?: selector.clear()
            }

            if (repository.isConnected()) {
                runCatching { repository.disconnect() }
            }

            runCatching {
                repository.disassociate(appContext, address)
                LocalDataStorage.removeDeviceAddress(appContext, address)
                deviceAssociationManager.syncAssociations()
                PerWatchCacheCleaner.wipe(appContext, address)
            }.onFailure {
                Timber.e(it, "Failed to forget watch $address")
            }

            tracker.remove(address)
            refresh()
        }
    }

    private fun loadPairedWatches(
        selected: String?,
        states: Map<String, ConnectionState>,
    ): List<PairedWatch> {
        val associations = runCatching { repository.getAssociationsWithNames(appContext) }
            .getOrDefault(emptyList())
        val localAddresses = LocalDataStorage.getDeviceAddresses(appContext)
        val addresses = (associations.map { it.address } + localAddresses)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        return addresses.map { address ->
            val association = associations.firstOrNull { it.address.equals(address, ignoreCase = true) }
            val rawName = association?.name ?: LocalDataStorage.getDeviceName(appContext, address)
            val name = rawName?.removePrefix("CASIO")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.no_watch)
            val isSelected = selected?.equals(address, ignoreCase = true) == true
            val state = states[address]
                ?: if (isSelected && repository.isConnected()) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
            val snapshot = WatchSnapshot.load(appContext, address)

            PairedWatch(
                address = address,
                name = name,
                state = state,
                snapshot = snapshot,
                hasSeenWatch = snapshot != null || syncHistoryStore.load(address).any { it.success },
                isSelected = isSelected,
            )
        }
    }
}
