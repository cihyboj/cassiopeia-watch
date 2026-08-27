package com.beamburst.casswatch.services

import com.beamburst.casswatch.utils.AppScopes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Fires [onInactivity] after [timeout] with no registered interaction, unless [isBusy]
 * reports an operation in flight — then the check is deferred instead of pulling the
 * BLE link out from under a running sync.
 */
class InactivityHandler(
    private val timeout: Duration,
    private val isBusy: () -> Boolean = { false },
    private val onInactivity: () -> Unit,
) {
    private val lastInteractionTime = MutableStateFlow(System.currentTimeMillis())

    private var monitoringJob: Job? = null

    fun registerInteraction() {
        lastInteractionTime.value = System.currentTimeMillis()
    }

    fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = AppScopes.main.launch {
            lastInteractionTime.collectLatest { lastInteraction ->
                delay(timeout.inWholeMilliseconds)
                checkInactivity(lastInteraction)
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    private fun checkInactivity(lastInteraction: Long) {
        if (!isInactive(lastInteraction)) return
        if (isBusy()) {
            // A sync is running; treat it as activity and re-arm the timer.
            registerInteraction()
            return
        }
        onInactivity()
    }

    private fun isInactive(lastInteraction: Long): Boolean =
        System.currentTimeMillis() - lastInteraction >= timeout.inWholeMilliseconds
}
