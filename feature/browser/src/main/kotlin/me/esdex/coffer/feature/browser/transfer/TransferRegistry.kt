package me.esdex.coffer.feature.browser.transfer

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import me.esdex.coffer.core.storage.TransferLimiter
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.sync.TransferKeys
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What running transfers share with the rest of the app, in memory only (SPEC §6): each file's DEK and
 * hash, progress, failures, and files ready to open. Nothing here survives the process; after a restart
 * transfers wait for the next unlock, which fills the keys in again.
 */
@Singleton
class TransferRegistry @Inject constructor() {

    /** Name and type for a file being decrypted to open in another app; known only while unlocked. */
    class OpenTarget(val name: String, val mime: String)

    /**
     * [source] is an upload's content URI, [target] an export's; both are sealed in the index and only
     * here in the clear, so a transfer can run while the vault is locked.
     */
    class Entry(
        val keys: TransferKeys,
        val source: String? = null,
        val target: String? = null,
        val open: OpenTarget? = null,
        val displayName: String? = null,
    )

    private val entries = mutableMapOf<Id, Entry>()

    /** At most this many transfers run at once, uploads and downloads together (SPEC §6). */
    val gate = Semaphore(TransferLimiter.MAX_TRANSFERS)

    /** Whether the queue's one run is going (see [TransferWorker]). */
    val queue = QueueState()

    /** Wakes the queue's run: new work, or a transfer of it ended. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    private val runs = HashMap<String, Job>()

    /** [job] runs the transfer [id], until [untrack]; so one transfer can be stopped and not the others. */
    @Synchronized
    fun track(id: String, job: Job) {
        runs[id] = job
    }

    @Synchronized
    fun untrack(id: String, job: Job) {
        if (runs[id] === job) runs.remove(id)
    }

    /** Stops the transfer [id] if it is running, and waits for it to let go. */
    suspend fun stop(id: String) {
        val job = synchronized(this) { runs[id] } ?: return
        job.cancelAndJoin()
    }

    @Synchronized
    fun put(fileId: Id, entry: Entry) {
        entries.put(fileId, entry)?.takeIf { it !== entry }?.keys?.close()
    }

    @Synchronized
    fun get(fileId: Id): Entry? = entries[fileId]

    @Synchronized
    fun has(fileId: Id): Boolean = fileId in entries

    @Synchronized
    fun remove(fileId: Id) {
        entries.remove(fileId)?.keys?.close()
    }

    @Synchronized
    fun clear() {
        entries.values.forEach { it.keys.close() }
        entries.clear()
    }

    enum class Phase {
        /** Waiting for one of the [gate]'s places. */
        QUEUED,

        /** Reading or encrypting with nothing on the wire (see [me.esdex.coffer.core.sync.TransferStage]). */
        PREPARING,
        MOVING,
    }

    /** Bytes of the encrypted file: what goes over the wire, a little more than the file itself. */
    data class Progress(val done: Long, val total: Long, val phase: Phase) {
        fun percent(): Int = if (total > 0) (done * 100 / total).toInt() else 0
    }

    private val progressFlow = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** By upload or download id. */
    val progress: StateFlow<Map<String, Progress>> = progressFlow.asStateFlow()

    fun queued(id: String) = progressFlow.update { it + (id to Progress(0, 0, Phase.QUEUED)) }
    fun progress(id: String, done: Long, total: Long, phase: Phase) = progressFlow.update { it + (id to Progress(done, total, phase)) }
    fun finished(id: String) = progressFlow.update { it - id }

    enum class Failure { NO_SPACE, SOURCE_CHANGED, SOURCE_GONE, CONTENT_MISMATCH, MISSING_PART, TARGET_FAILED }

    private val failureFlow = MutableStateFlow<Map<String, Failure>>(emptyMap())
    val failures: StateFlow<Map<String, Failure>> = failureFlow.asStateFlow()

    fun fail(id: String, failure: Failure) = failureFlow.update { it + (id to failure) }
    fun clearFailure(id: String) = failureFlow.update { it - id }

    class ReadyToOpen(val file: File, val mime: String)

    private val openFlow = MutableSharedFlow<ReadyToOpen>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Decrypted files the UI should hand to another app; dropped if no screen is listening. */
    val readyToOpen: SharedFlow<ReadyToOpen> = openFlow.asSharedFlow()

    fun readyToOpen(file: File, mime: String) {
        openFlow.tryEmit(ReadyToOpen(file, mime))
    }
}
