package com.beamburst.casswatch.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.SyncEntry
import com.beamburst.casswatch.data.local.SyncHistoryStore
import com.beamburst.casswatch.utils.LocalDataStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncHistoryViewModel @Inject constructor(
    private val store: SyncHistoryStore,
    private val watchSelector: CurrentWatchSelector,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val currentAddr: String? = watchSelector.currentAddress()

    private val _entries = MutableStateFlow<List<SyncEntry>>(emptyList())
    val entries: StateFlow<List<SyncEntry>> = _entries.asStateFlow()

    private val _expandedTimestamp = MutableStateFlow<Long?>(null)
    val expandedTimestamp: StateFlow<Long?> = _expandedTimestamp.asStateFlow()

    val watchName: String = currentAddr
        ?.let { LocalDataStorage.getDeviceName(context, it)?.removePrefix("CASIO")?.trim() }
        ?: ""

    init {
        viewModelScope.launch {
            currentAddr?.let { addr ->
                store.observe(addr).collect { _entries.value = it }
            }
        }
    }

    fun toggleExpand(timestamp: Long) {
        _expandedTimestamp.value = if (_expandedTimestamp.value == timestamp) null else timestamp
    }

    fun clear() {
        viewModelScope.launch {
            currentAddr?.let { store.clear(it) }
        }
    }
}
