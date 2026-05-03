package com.beamburst.casswatch.data.local

import android.content.Context
import com.beamburst.casswatch.utils.LocalDataStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface CurrentWatchSelector {
    fun currentAddress(): String?
}

@Singleton
class LocalDataCurrentWatchSelector @Inject constructor(
    @ApplicationContext private val context: Context,
) : CurrentWatchSelector {
    override fun currentAddress(): String? =
        LocalDataStorage.get(context, "LastDeviceAddress", null)
            ?: LocalDataStorage.getDeviceAddresses(context).firstOrNull()
}
