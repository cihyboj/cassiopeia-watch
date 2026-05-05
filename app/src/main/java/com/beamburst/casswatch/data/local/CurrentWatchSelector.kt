package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.Utils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentWatchSelector @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        const val LAST_DEVICE_ADDRESS_KEY = "LastDeviceAddress"
    }

    private val _selected = MutableStateFlow(
        LocalDataStorage.get(context, LAST_DEVICE_ADDRESS_KEY, null)
            ?.let { Utils.sanitizeMacAddress(it) }
            ?.takeIf { it.isNotBlank() }
            ?: LocalDataStorage.getDeviceAddresses(context).firstOrNull()
    )
    val selected: StateFlow<String?> = _selected.asStateFlow()

    fun currentAddress(): String? = _selected.value

    suspend fun select(address: String) {
        val normalizedAddress = Utils.sanitizeMacAddress(address)
        if (normalizedAddress.isBlank()) return
        _selected.value = normalizedAddress
        LocalDataStorage.putAsync(context, LAST_DEVICE_ADDRESS_KEY, normalizedAddress)
    }

    suspend fun clear() {
        _selected.value = null
        LocalDataStorage.deleteAsync(context, LAST_DEVICE_ADDRESS_KEY)
    }
}
