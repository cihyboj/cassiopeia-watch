package com.beamburst.casswatch.data.local

import com.beamburst.casswatch.utils.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** The live BLE session identity; selecting a watch must never change it. */
@Singleton
class WatchConnectionSession @Inject constructor() {
    private val connectedAddress = MutableStateFlow<String?>(null)
    val address: String? get() = connectedAddress.value

    fun established(address: String) {
        connectedAddress.value = Utils.sanitizeMacAddress(address).takeIf { it.isNotBlank() }
    }

    fun disconnected() {
        connectedAddress.value = null
    }

    /**
     * Clears the session only if it still belongs to [address]. A Disconnect event for the
     * previous watch can be delivered after the next watch has already connected.
     */
    fun disconnected(address: String?) {
        val ended = address?.let(Utils::sanitizeMacAddress).orEmpty()
        if (ended.isBlank()) {
            disconnected()
            return
        }
        connectedAddress.update { current ->
            if (current.equals(ended, ignoreCase = true)) null else current
        }
    }

    /** Event subscribers can run after the API's connection waiter has resumed. */
    suspend fun awaitAddress(): String = connectedAddress.filterNotNull().first()
}
