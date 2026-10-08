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
import com.beamburst.casswatch.pairing.WatchConnectCoordinator
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.Utils
import com.beamburst.casswatch.utils.SyncHistoryLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
    private val connectCoordinator: WatchConnectCoordinator,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val refreshTick = MutableStateFlow(0L)

    val paired: StateFlow<List<PairedWatch>> =
        combine(selector.selected, tracker.states, refreshTick) { selected, states, _ ->
            loadPairedWatches(selected, states)
        }
            // loadPairedWatches does binder + DataStore I/O and re-runs on every
            // connection-state change; keep it off the main thread and idle when
            // nothing observes it.
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshTick.value = System.currentTimeMillis()
        }
    }

    fun select(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedAddress = Utils.sanitizeMacAddress(address)
            if (normalizedAddress.isBlank()) return@launch
            val selectedWatch = paired.value.firstOrNull {
                it.address.equals(normalizedAddress, ignoreCase = true)
            }
            if (
                selectedWatch?.state != ConnectionState.CONNECTED &&
                tracker.states.value.stateFor(normalizedAddress) == null
            ) {
                tracker.mark(normalizedAddress, ConnectionState.DISCONNECTED)
            }
            selector.select(normalizedAddress)
            refresh()
        }
    }

    fun connect(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedAddress = Utils.sanitizeMacAddress(address)
            if (normalizedAddress.isBlank()) return@launch
            selector.select(normalizedAddress)
            val previouslySelected = paired.value.firstOrNull {
                it.state == ConnectionState.CONNECTED &&
                    !it.address.equals(normalizedAddress, ignoreCase = true)
            }?.address

            runCatching {
                previouslySelected?.let { tracker.mark(it, ConnectionState.DISCONNECTED) }
                if (repository.isConnected()) repository.disconnect()
                tracker.mark(normalizedAddress, ConnectionState.CONNECTING)
                if (!connectCoordinator.connect(normalizedAddress, takeover = true)) {
                    error("Connection attempt timed out")
                }
                tracker.mark(normalizedAddress, ConnectionState.CONNECTED)
                syncHistoryLogger.record(
                    trigger = SyncTrigger.CONNECTION_CONNECTED,
                    kinds = setOf(SyncKind.CONNECTION),
                    success = true,
                    details = SyncDetails.Empty.copy(connection = "Started from watch row Connect menu"),
                )
            }.onFailure {
                Timber.e(it, "Failed to connect watch $normalizedAddress")
                tracker.mark(normalizedAddress, ConnectionState.ERROR)
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
            val normalizedAddress = Utils.sanitizeMacAddress(address)
            if (normalizedAddress.isBlank()) return@launch
            runCatching {
                repository.disconnect()
                tracker.mark(normalizedAddress, ConnectionState.DISCONNECTED)
                syncHistoryLogger.record(
                    trigger = SyncTrigger.CONNECTION_DISCONNECTED,
                    kinds = setOf(SyncKind.CONNECTION),
                    success = true,
                    details = SyncDetails.Empty.copy(connection = "Started from watch row Disconnect menu"),
                )
            }.onFailure {
                Timber.e(it, "Failed to disconnect watch $normalizedAddress")
                tracker.mark(normalizedAddress, ConnectionState.ERROR)
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
            val normalizedAddress = Utils.sanitizeMacAddress(address)
            if (normalizedAddress.isBlank()) return@launch
            val remaining = LocalDataStorage.getDeviceAddresses(appContext)
                .filterNot { it.equals(normalizedAddress, ignoreCase = true) }

            if (selector.currentAddress()?.equals(normalizedAddress, ignoreCase = true) == true) {
                remaining.firstOrNull()?.let { selector.select(it) } ?: selector.clear()
            }

            if (repository.isConnected()) {
                runCatching { repository.disconnect() }
            }

            runCatching {
                repository.disassociate(appContext, normalizedAddress)
                LocalDataStorage.removeDeviceAddress(appContext, normalizedAddress)
                deviceAssociationManager.syncAssociations()
                PerWatchCacheCleaner.wipe(appContext, normalizedAddress)
            }.onFailure {
                Timber.e(it, "Failed to forget watch $normalizedAddress")
            }

            tracker.remove(normalizedAddress)
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
            .map { Utils.sanitizeMacAddress(it) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        return addresses.map { address ->
            val association = associations.firstOrNull {
                Utils.sanitizeMacAddress(it.address).equals(address, ignoreCase = true)
            }
            val rawName = association?.name ?: LocalDataStorage.getDeviceName(appContext, address)
            val name = rawName?.removePrefix("CASIO")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.no_watch)
            val isSelected = selected?.equals(address, ignoreCase = true) == true
            val state = states.stateFor(address)
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

    private fun Map<String, ConnectionState>.stateFor(address: String): ConnectionState? =
        this[address] ?: entries.firstOrNull { it.key.equals(address, ignoreCase = true) }?.value
}
