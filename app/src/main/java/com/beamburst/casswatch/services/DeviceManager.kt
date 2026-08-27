package com.beamburst.casswatch.services

import com.beamburst.casswatch.utils.AppScopes
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.Utils
import org.avmedia.gshockapi.EventAction
import org.avmedia.gshockapi.ProgressEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceManager @Inject constructor(
    private val repository: GShockRepository,
    @param:ApplicationContext private val appContext: Context
) {
    companion object {
        private const val EVENT_SUBSCRIBER_ID = "DeviceManager"
    }

    private data class DeviceInfo(
        val name: String,
        val address: String
    )

    init {
        initializeEventListener()
    }

    private fun initializeEventListener() {
        val eventActions = createEventActions()
        ProgressEvents.runEventActions(EVENT_SUBSCRIBER_ID, eventActions)
    }

    private fun createEventActions(): Array<EventAction> = arrayOf(
        EventAction("DeviceName") { handleDeviceNameEvent() },
        EventAction("DeviceAddress") { handleDeviceAddressEvent() }
    )

    private fun handleDeviceNameEvent() {
        ProgressEvents.getPayload("DeviceName")?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?.let { deviceName ->
                when {
                    deviceName.isEmpty() -> LocalDataStorage.delete(appContext, "LastDeviceName")
                    isValidDeviceName(deviceName) -> updateDeviceName(deviceName)
                }
            }
    }

    private fun isValidDeviceName(deviceName: String): Boolean =
        deviceName.contains("CASIO") &&
                LocalDataStorage.get(appContext, "LastDeviceName", "") != deviceName

    private fun updateDeviceName(deviceName: String) {
        LocalDataStorage.put(appContext, "LastDeviceName", deviceName)
    }

    private fun handleDeviceAddressEvent() {
        ProgressEvents.getPayload("DeviceAddress")?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?.let { deviceAddress ->
                when {
                    deviceAddress.isEmpty() -> LocalDataStorage.delete(
                        appContext,
                        "LastDeviceAddress"
                    )

                    isValidDeviceAddress(deviceAddress) -> updateDeviceAddress(deviceAddress)
                }
            }
    }

    private fun isValidDeviceAddress(address: String): Boolean =
        Utils.sanitizeMacAddress(address).let { normalizedAddress ->
            LocalDataStorage.get(appContext, "LastDeviceAddress", "") != normalizedAddress &&
                    repository.validateBluetoothAddress(normalizedAddress)
        }

    private fun updateDeviceAddress(address: String) {
        val normalizedAddress = Utils.sanitizeMacAddress(address)
        LocalDataStorage.put(appContext, "LastDeviceAddress", normalizedAddress)
        AppScopes.io.launch {
            LocalDataStorage.addDeviceAddress(appContext, normalizedAddress)
        }
    }

    fun saveLastConnectedDevice(name: String, address: String) {
        DeviceInfo(name, address).let { deviceInfo ->
            val normalizedAddress = Utils.sanitizeMacAddress(deviceInfo.address)
            LocalDataStorage.put(appContext, "LastDeviceName", deviceInfo.name)
            LocalDataStorage.put(appContext, "LastDeviceAddress", normalizedAddress)
        }
    }

    fun clearLastConnectedDevice() {
        listOf("LastDeviceName", "LastDeviceAddress").forEach { key ->
            LocalDataStorage.delete(appContext, key)
        }
    }
}
