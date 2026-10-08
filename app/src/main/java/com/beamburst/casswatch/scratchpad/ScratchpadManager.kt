// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.scratchpad

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avmedia.gshockapi.IGShockAPI
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * The ScratchpadManager serves as the central coordinator for all scratchpad data operations.
 * It maintains a "master buffer" (byte array) that represents the state of the watch's scratchpad memory.
 *
 * Responsibilities:
 * - Aggregating data requirements from multiple [ScratchpadClient]s.
 * - Managing the lifecycle of data synchronization (load from watch, save to watch).
 * - Distributing relevant data slices to registered clients based on their offsets.
 * - Collecting data from clients to form the payload for writing to the watch.
 */
class ScratchpadManager @Inject constructor(
    private val api: IGShockAPI
) {
    private val clients = mutableListOf<ScratchpadClient>()
    private var masterBuffer: ByteArray = ByteArray(0)

    /** load() and save() are reachable concurrently from event-driven coroutines. */
    private val ioMutex = Mutex()

    /**
     * Clients call this method to register themselves with the manager.
     * The layout is determined by each client's fixed offset, not registration order.
     */
    fun register(client: ScratchpadClient) {
        if (!clients.contains(client)) {
            clients.add(client)
            updateBufferSize()
        }
    }


    /**
     * Updates the master buffer with the data from a specific client.
     * This is useful for clients that want to commit their in-memory changes
     * to the manager's central buffer without triggering a full save-to-watch cycle.
     *
     * @param client The client whose data should be written to the master buffer.
     */
    fun updateMasterBuffer(client: ScratchpadClient) {
        val offset = client.getStorageOffset()
        val clientBuffer = client.getBuffer()
        if (offset + clientBuffer.size <= masterBuffer.size) {
            clientBuffer.copyInto(destination = masterBuffer, destinationOffset = offset)
        }
    }

    /**
     * Calculates the required buffer size based on all registered clients' offsets and sizes.
     */
    private fun updateBufferSize() {
        if (clients.isEmpty()) {
            masterBuffer = ByteArray(0)
            return
        }
        
        // Find the maximum end position (offset + size) across all clients
        val maxEndPosition = clients.maxOf { it.getStorageOffset() + it.getStorageSize() }
        
        // Only resize if needed
        if (masterBuffer.size < maxEndPosition) {
            val newBuffer = ByteArray(maxEndPosition)
            // Preserve existing data
            masterBuffer.copyInto(newBuffer, 0, 0, masterBuffer.size)
            masterBuffer = newBuffer
        }
    }

    /**
     * Loads data from the watch and distributes it to all registered clients.
     */
    internal suspend fun load() = ioMutex.withLock {
        if (masterBuffer.isEmpty()) return@withLock
        val data = api.getScratchpadData(0, masterBuffer.size)
        if (data.size != masterBuffer.size) {
            // Clients keep their defaults; a later save() would overwrite the watch with
            // them, so make the mismatch visible instead of failing silently.
            Timber.w(
                "Scratchpad read returned %d bytes, expected %d — keeping client defaults",
                data.size, masterBuffer.size,
            )
            return@withLock
        }
        masterBuffer = data

        clients.forEach { client ->
            val offset = client.getStorageOffset()
            val size = client.getStorageSize()
            if (offset + size <= masterBuffer.size) {
                val slice = masterBuffer.copyOfRange(offset, offset + size)
                client.setBuffer(slice)
            }
        }
    }

    /**
     * Gathers data from all clients and saves the combined buffer to the watch.
     */
    internal suspend fun save() = ioMutex.withLock {
        if (masterBuffer.isEmpty()) return@withLock
        clients.forEach { client ->
            val offset = client.getStorageOffset()
            val clientBuffer = client.getBuffer()
            if (offset + clientBuffer.size <= masterBuffer.size) {
                clientBuffer.copyInto(destination = masterBuffer, destinationOffset = offset)
            }
        }
        api.setScratchpadData(masterBuffer, 0)
    }
}
