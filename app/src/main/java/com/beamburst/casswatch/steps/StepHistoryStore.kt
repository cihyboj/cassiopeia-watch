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
    fun load(address: String): StepHistory {
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

    suspend fun update(address: String, transform: (StepHistory) -> StepHistory) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = load(address)
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
        withContext(Dispatchers.IO) { mutex.withLock { atomicFile(address).delete() } }
        changes.value = System.nanoTime()
    }

    private fun atomicFile(address: String): AtomicFile =
        AtomicFile(File(File(context.filesDir, DIR), address.replace(":", "") + ".json"))

    companion object {
        // Shared by every instance (PerWatchCacheCleaner creates its own): one lock and one
        // change signal, so a wipe cannot race an update and observers see it.
        private val changes = MutableStateFlow(0L)
        private val mutex = Mutex()

        private const val DIR = "step_history"
        private const val ENABLED_KEY = "StepReadingEnabled"

        operator fun invoke(context: Context): StepHistoryStore = StepHistoryStore(context.applicationContext)

        fun isReadingEnabled(context: Context): Boolean =
            LocalDataStorage.get(context, ENABLED_KEY, "true")?.toBoolean() ?: true

        suspend fun setReadingEnabled(context: Context, enabled: Boolean) {
            LocalDataStorage.putAsync(context, ENABLED_KEY, enabled.toString())
        }
    }
}
