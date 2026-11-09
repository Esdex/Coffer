package me.esdex.coffer.feature.browser.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.esdex.coffer.core.crypto.Id

/**
 * A delete held back for [delayMillis] so it can be taken back: until then nothing goes to Telegram,
 * where a delete is final, and the items are only hidden. Only the latest can be taken back; a new
 * delete sends the one before at once, and so does [flush], for when the app leaves the screen or
 * locks and the keys a delete needs are about to go.
 */
class DeferredDeletes(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val send: suspend (Batch) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    data class Batch(val folders: List<Id>, val files: List<Id>) {
        val all: Set<Id> get() = (folders + files).toSet()
        val size: Int get() = folders.size + files.size
    }

    private val lock = Mutex()
    private var pending: Pair<Batch, Job>? = null

    private val hiddenFlow = MutableStateFlow<Set<Id>>(emptySet())

    /** Deleted on screen, not yet in Telegram: the browser leaves them out. */
    val hidden: StateFlow<Set<Id>> = hiddenFlow.asStateFlow()

    suspend fun schedule(batch: Batch) {
        flush()
        lock.withLock {
            hiddenFlow.update { it + batch.all }
            pending = batch to scope.launch {
                delay(delayMillis)
                if (take(batch)) sendShown(batch)
            }
        }
    }

    /** True if the latest delete was taken back in time; false once it has gone out. */
    suspend fun undo(): Boolean = lock.withLock {
        val (batch, job) = pending ?: return false
        pending = null
        job.cancel()
        hiddenFlow.update { it - batch.all }
        true
    }

    /** Sends the delete held back, if any, now. */
    suspend fun flush() {
        val held = lock.withLock { pending.also { pending = null } } ?: return
        held.second.cancel()
        sendShown(held.first)
    }

    private suspend fun take(batch: Batch): Boolean = lock.withLock {
        if (pending?.first !== batch) return false
        pending = null
        true
    }

    // Shown again afterwards either way: gone from the index when it worked, back in sight when not.
    private suspend fun sendShown(batch: Batch) {
        try {
            send(batch)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onFailure(e)
        } finally {
            hiddenFlow.update { it - batch.all }
        }
    }
}
