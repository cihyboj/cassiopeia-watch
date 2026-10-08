package com.beamburst.casswatch.pairing

import com.beamburst.casswatch.utils.AppScopes
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.beamburst.casswatch.data.repository.GShockRepository
import com.beamburst.casswatch.utils.CrashReportHelper
import com.beamburst.casswatch.utils.LocalDataStorage
import com.beamburst.casswatch.utils.Utils
import org.avmedia.gshockapi.ProgressEvents
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceAssociationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: GShockRepository
) {
    var pendingCrashLog by mutableStateOf<String?>(null)

    @Volatile
    private var activeScanAddresses: Set<String>? = null

    /** syncAssociations() is invoked from init, pairing, forget, and boot; serialize runs. */
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun init() {
        recoverFromPairingCrash()
        // Intentionally NOT calling cleanupLocalStorage() on every startup.
        // It removed any DataStore address not present in the system CDM association list,
        // which destroyed legitimate state in cases where the two stores can diverge:
        //   - fresh backup imports (DataStore has entries, CDM doesn't yet)
        //   - system-level "Clear data" that drops CDM associations but the user later
        //     restored from backup
        //   - manual CDM unpair without going through the app
        // Stale entries are removed via the explicit "Forget watch" action in the watch
        // list; we don't need (and shouldn't have) an automatic GC.
        syncAssociations()
    }

    /** Recover from a previous pairing crash by clearing potentially corrupted state */
    private suspend fun recoverFromPairingCrash() {
        try {
            if (CrashReportHelper.hasPairingCrashFlag(context)) {
                Timber.w("Detected previous pairing crash, attempting recovery")

                val latestCrash = CrashReportHelper.getLatestCrashLog(context)

                // Set the pending log to be picked up by the UI
                if (latestCrash != null) {
                    pendingCrashLog = latestCrash
                    Timber.e("Previous crash log captured for display")
                }

                CrashReportHelper.clearPairingCrashFlag(context)
                Timber.i("Pairing crash recovery completed")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to recover from pairing crash")
        }
    }

    suspend fun cleanupLocalStorage() {
        val repositoryAssociations = repository.getAssociations(context)
            .map { Utils.sanitizeMacAddress(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val localAddresses = LocalDataStorage.getDeviceAddresses(context)

        // 1. Identify which addresses SHOULD be removed
        val addressesToRemove = localAddresses.filter { it !in repositoryAssociations }

        // 2. Remove them one by one (Safe here because we aren't modifying 'localAddresses')
        addressesToRemove.forEach { address ->
            Timber.i("Cleaning up orphaned local association: $address")
            LocalDataStorage.removeDeviceAddress(context, address)
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    @SuppressLint("NewApi")
    fun syncAssociations() {
        AppScopes.io.launch {
            syncMutex.withLock {
            // 1. Get the list of associations the system knows about
            val systemAssociations = repository.getAssociationsWithNames(context)

            // 2. Auto-Discovery: Add any system associations to LocalDataStorage if not already there
            systemAssociations.forEach { association ->
                val normalizedAddress = Utils.sanitizeMacAddress(association.address)
                if (normalizedAddress.isBlank()) return@forEach
                LocalDataStorage.addDeviceAddress(context, normalizedAddress)
                association.name?.let {
                    LocalDataStorage.setDeviceName(context, normalizedAddress, it)
                }
            }

            // 3. Update the "LastDeviceAddress" if none is set
            if (LocalDataStorage.get(context, "LastDeviceAddress", "").isNullOrEmpty()) {
                systemAssociations
                    .map { Utils.sanitizeMacAddress(it.address) }
                    .firstOrNull { it.isNotBlank() }
                    ?.let {
                        LocalDataStorage.putAsync(
                            context,
                            "LastDeviceAddress",
                            it
                        )
                    }
            }

            // 4. Re-fetch the final list of active addresses after auto-discovery
            val finalActiveAddresses =
                LocalDataStorage.getDeviceAddresses(context)
                    .map { Utils.sanitizeMacAddress(it) }
                    .filter { it.isNotBlank() }
                    .toSet()

            if (!isLocationEnabled()) {
                Timber.w("Location Services disabled — notifying user.")
                ProgressEvents.onNext("LocationServicesDisabled")
                return@withLock
            }

            val cdm = context.getSystemService(android.companion.CompanionDeviceManager::class.java)

            when (DevicePresenceObservationPolicy.modeForSdk(Build.VERSION.SDK_INT)) {
                DevicePresenceObservationMode.REQUEST_BASED -> {
                    cdm.myAssociations.forEach { associationInfo ->
                        val macAddress = associationInfo.deviceMacAddress
                            ?.toString()
                            ?.let { Utils.sanitizeMacAddress(it) }
                            ?.takeIf { it.isNotBlank() }
                            ?: return@forEach
                        manageRequestBasedDevicePresence(
                            cdm = cdm,
                            associationInfo = associationInfo,
                            macAddress = macAddress,
                            shouldObserve = macAddress in finalActiveAddresses,
                        )
                    }
                }

                DevicePresenceObservationMode.ADDRESS_BASED -> {
                    addressBasedAssociationMacs(cdm).forEach { mac ->
                        manageAddressBasedDevicePresence(
                            cdm = cdm,
                            macAddress = mac,
                            shouldObserve = mac in finalActiveAddresses,
                        )
                    }
                }

                DevicePresenceObservationMode.FALLBACK_ONLY -> {
                    Timber.i("CDM presence observation not available on API ${Build.VERSION.SDK_INT}, relying on fallback scan")
                }
            }

            // Start fallback scan for all active addresses (runs on all API levels)
            startFallbackScan(finalActiveAddresses.toList())
            }
        }
    }

    @SuppressLint("NewApi")
    private fun manageRequestBasedDevicePresence(
        cdm: android.companion.CompanionDeviceManager,
        associationInfo: android.companion.AssociationInfo,
        macAddress: String,
        shouldObserve: Boolean
    ) {
        try {
            val request = android.companion.ObservingDevicePresenceRequest.Builder()
                .setAssociationId(associationInfo.id)
                .build()

            if (shouldObserve) {
                cdm.startObservingDevicePresence(request)
                Timber.d("CDM presence started (request-based) for $macAddress")
            } else {
                cdm.stopObservingDevicePresence(request)
                Timber.d("CDM presence stopped for $macAddress")
            }
        } catch (e: Throwable) {
            Timber.e(e, "Request-based CDM presence failed for $macAddress; fallback scan remains active")
        }
    }

    @SuppressLint("NewApi")
    private fun addressBasedAssociationMacs(
        cdm: android.companion.CompanionDeviceManager,
    ): Set<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations
                .mapNotNull { associationInfo ->
                    associationInfo.deviceMacAddress
                        ?.toString()
                        ?.let { Utils.sanitizeMacAddress(it) }
                        ?.takeIf { address -> address.isNotBlank() }
                }
                .toSet()
        } else {
            @Suppress("DEPRECATION")
            cdm.associations
                .map { Utils.sanitizeMacAddress(it) }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    @SuppressLint("NewApi")
    private fun manageAddressBasedDevicePresence(
        cdm: android.companion.CompanionDeviceManager,
        macAddress: String,
        shouldObserve: Boolean,
    ) {
        try {
            if (shouldObserve) {
                repository.startObservingDevicePresence(context, macAddress)
                Timber.d("CDM presence started (address-based) for $macAddress")
            } else {
                @Suppress("DEPRECATION")
                cdm.stopObservingDevicePresence(macAddress)
                Timber.d("CDM presence stopped (address-based) for $macAddress")
            }
        } catch (e: Exception) {
            Timber.e(e, "Address-based CDM presence failed for $macAddress; fallback scan remains active")
        }
    }

    @SuppressLint("NewApi")
    suspend fun checkPairedDevicesOrNotify() {
        val associations = repository.getAssociationsWithNames(context)
        if (associations.isEmpty() && LocalDataStorage.getDeviceAddresses(context).isEmpty()) {
            ProgressEvents.onNext("NoPairedDevices")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startFallbackScan(addresses: List<String>) {
        val newAddressesSet = addresses
            .map { Utils.sanitizeMacAddress(it) }
            .filter { it.isNotBlank() }
            .toSet()

        // Optimization: If the addresses haven't changed, don't restart the scan
        if (activeScanAddresses == newAddressesSet) {
            Timber.d("Fallback scan already running for these addresses. Skipping restart.")
            return
        }

        // Create a PendingIntent to identify the scan
        val intent =
            Intent(context, com.beamburst.casswatch.receivers.BleScanReceiver::class.java)
        val pendingIntent =
            android.app.PendingIntent.getBroadcast(
                context,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_MUTABLE
            )

        repository.startFallbackScan(context, newAddressesSet.toList(), pendingIntent)

        activeScanAddresses = if (newAddressesSet.isEmpty()) {
            null
        } else {
            newAddressesSet
        }
    }
}
