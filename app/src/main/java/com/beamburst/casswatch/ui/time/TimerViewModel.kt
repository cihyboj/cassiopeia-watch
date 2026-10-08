package com.beamburst.casswatch.ui.time

import com.beamburst.casswatch.data.local.WatchConnectionSession
import com.beamburst.casswatch.pairing.WatchWriteGuard
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beamburst.casswatch.R
import com.beamburst.casswatch.data.local.CurrentWatchSelector
import com.beamburst.casswatch.data.local.WatchConnectionTracker
import com.beamburst.casswatch.data.local.WatchSnapshot
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.ui.common.AppSnackbar
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import javax.inject.Inject

data class TimerState(
    val timer: Int = 0,
)

@HiltViewModel
class TimerViewModel @Inject constructor(
    private val api: GShockRepository,
    private val connectionSession: WatchConnectionSession,
    private val writeGuard: WatchWriteGuard,
    @param:ApplicationContext private val appContext: Context,
    private val watchSelector: CurrentWatchSelector,
    private val connectionTracker: WatchConnectionTracker,
) : ViewModel() {
    private val _state = MutableStateFlow(TimerState())
    val state = _state.asStateFlow()

    private val progressEventsSubscriberId = "TimerViewModel-${System.identityHashCode(this)}"

    init {
        ProgressEvents.runEventActions(
            progressEventsSubscriberId,
            arrayOf(
                EventAction("WatchInitializationCompleted") { refreshTimer() },
                EventAction("ConnectionSetupComplete") { refreshTimer() },
                EventAction("Disconnect") { loadCachedTimer(watchSelector.currentAddress()) },
                EventAction("DeviceDisappeared") { loadCachedTimer(watchSelector.currentAddress()) },
                EventAction("ConnectionFailed") { loadCachedTimer(watchSelector.currentAddress()) },
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            watchSelector.selected.collect { addr ->
                loadCachedTimer(addr)
                if (isSelectedWatchConnected(addr)) refreshTimer()
            }
        }
    }

    override fun onCleared() {
        ProgressEvents.subscriber.stop(progressEventsSubscriberId)
        super.onCleared()
    }

    fun setTimer(hours: Int, minutes: Int, seconds: Int) {
        val timer = hours * 3600 + minutes * 60 + seconds
        _state.value = TimerState(timer = timer)
        saveTimer(timer)
    }

    fun sendTimerToWatch() {
        val ready = writeGuard.capture() ?: return
        val timer = _state.value.timer
        viewModelScope.launch {
            writeGuard.run(ready) { checkSession ->
                com.beamburst.casswatch.utils.WatchSyncActivity.track {
                    checkSession()
                    api.setTimer(timer)
                    checkSession()
                    saveTimer(timer, ready.address)
                    AppSnackbar(appContext.getString(R.string.timer_set))
                }
            }
        }
    }

    private fun refreshTimer() = viewModelScope.launch(Dispatchers.IO) {
        val addr = watchSelector.currentAddress()
        if (!isSelectedWatchConnected(addr)) {
            loadCachedTimer(addr)
            return@launch
        }

        val cachedTimer = addr
            ?.let { WatchSnapshot.load(appContext, it) }
            ?.timer
        if (cachedTimer != null) {
            _state.value = TimerState(timer = cachedTimer)
            return@launch
        }

        runCatching {
            val ready = connectionSession.matching(addr) ?: return@runCatching
            val timer = api.getTimer()
            if (!connectionSession.isCurrent(ready)) return@runCatching
            _state.value = TimerState(timer = timer)
            saveTimer(timer, ready.address)
        }.onFailure {
            AppSnackbar(appContext.getString(R.string.api_error))
        }
    }

    private fun loadCachedTimer(address: String?) {
        val snapshot = address?.let { WatchSnapshot.load(appContext, it) }
        _state.value = TimerState(timer = snapshot?.timer ?: 0)
    }

    private fun saveTimer(timer: Int, address: String? = watchSelector.currentAddress()) {
        val addr = address ?: return
        val current = WatchSnapshot.load(appContext, addr)
        WatchSnapshot.save(
            appContext,
            addr,
            WatchSnapshot(
                timer = timer,
                battery = current?.battery,
                temperature = current?.temperature,
                homeTime = current?.homeTime,
                cachedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun isSelectedWatchConnected(addr: String?): Boolean =
        api.isConnected() && connectionSession.matching(addr) != null

}
