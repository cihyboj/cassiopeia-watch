package com.beamburst.casswatch.steps

import android.content.Context
import android.util.AtomicFile
import com.beamburst.casswatch.utils.LocalDataStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step history, one JSON file per watch under `files/step_history/`. Kept out of DataStore on
 * purpose: backup rules include only `datastore/`, so step data never leaves the phone.
 */
@Singleton
class StepHistoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Reads under the write lock: AtomicFile.readFully() deletes an in-progress `.new` file
     * when the base file exists, so an unlocked read during [update] would lose that write.
     */
    suspend fun load(address: String): StepHistory =
        withContext(Dispatchers.IO) { mutex.withLock { loadLocked(address) } }

    private fun loadLocked(address: String): StepHistory {
        // readFully() also recovers the previous version if a write was interrupted.
        val bytes = try {
            atomicFile(address).readFully()
        } catch (_: FileNotFoundException) {
            return StepHistory()
        } catch (e: IOException) {
            Timber.w(e, "Step history unreadable")
            return StepHistory()
        }
        return StepHistoryJson.decode(bytes.decodeToString())
    }

    /**
     * Changes whenever [wipe] runs for [address]. Work that started before a wipe passes the
     * value it saw to [update] / [saveRaw], so it cannot recreate a forgotten watch's data.
     */
    fun generation(address: String): Long = synchronized(wipes) { wipes[fileKey(address)] ?: 0L }

    private fun stale(address: String, generation: Long?) = generation != null && generation != generation(address)

    suspend fun update(
        address: String,
        generation: Long? = null,
        transform: (StepHistory) -> StepHistory,
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (stale(address, generation)) return@withLock
                val current = loadLocked(address)
                val next = transform(current)
                if (next == current) return@withLock
                val file = atomicFile(address)
                file.baseFile.parentFile?.mkdirs()
                // AtomicFile keeps the previous version until the new one is fully written.
                val out = file.startWrite()
                try {
                    out.write(StepHistoryJson.encode(next).encodeToByteArray())
                    file.finishWrite(out)
                } catch (e: Exception) {
                    file.failWrite(out)
                    throw e
                }
            }
        }
        changes.value = System.nanoTime()
    }

    fun observe(address: String): Flow<StepHistory> =
        changes
            .map { load(address) }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()

    suspend fun wipe(address: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                synchronized(wipes) { wipes[fileKey(address)] = (wipes[fileKey(address)] ?: 0L) + 1 }
                atomicFile(address).delete()
                rawDir(address).deleteRecursively()
                // Debug builds before per-watch folders kept raw records directly in raw/.
                rawDir(address).parentFile?.listFiles { f -> f.isFile }?.forEach { it.delete() }
            }
        }
        changes.value = System.nanoTime()
    }

    /**
     * Keeps the raw record of a read that closed the watch's transaction (the watch no longer
     * has it), until the format is verified. Per watch, so [wipe] removes them too.
     */
    suspend fun saveRaw(address: String, raw: ByteArray, atMs: Long, generation: Long? = null) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (stale(address, generation)) return@withLock
                runCatching {
                    val dir = rawDir(address).apply { mkdirs() }
                    File(dir, "$atMs.hex").writeText(raw.joinToString("") { "%02x".format(it) })
                    dir.listFiles()?.sortedBy { it.name }?.dropLast(MAX_RAW_FILES)?.forEach { it.delete() }
                }.onFailure { Timber.w(it, "Raw step record not saved") }
            }
        }
    }

    private fun fileKey(address: String) = address.replace(":", "")

    private fun atomicFile(address: String): AtomicFile =
        AtomicFile(File(File(context.filesDir, DIR), fileKey(address) + ".json"))

    private fun rawDir(address: String): File = File(File(File(context.filesDir, DIR), "raw"), fileKey(address))

    companion object {
        // Shared by every instance (PerWatchCacheCleaner creates its own): one lock and one
        // change signal, so a wipe cannot race an update and observers see it.
        private val changes = MutableStateFlow(0L)
        private val mutex = Mutex()
        private val wipes = mutableMapOf<String, Long>()

        private const val DIR = "step_history"
        private const val MAX_RAW_FILES = 60
        private const val ENABLED_KEY = "StepReadingEnabled"

        operator fun invoke(context: Context): StepHistoryStore = StepHistoryStore(context.applicationContext)

        fun isReadingEnabled(context: Context): Boolean =
            LocalDataStorage.get(context, ENABLED_KEY, "true")?.toBoolean() ?: true

        suspend fun setReadingEnabled(context: Context, enabled: Boolean) {
            LocalDataStorage.putAsync(context, ENABLED_KEY, enabled.toString())
        }
    }
}
