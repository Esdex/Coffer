package me.esdex.coffer.feature.browser.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.MessageId

/**
 * Which vault files are open for reading in place, so the pieces TDLib downloaded for one are let go
 * when its last reader closes, not by the first. A file closed and opened again at once (Next then
 * Previous, a picture swiped away and back) would otherwise have its pieces deleted under the new
 * reader, which then downloads them again.
 */
class ReadLeases(private val scope: CoroutineScope) {

    private class Lease {
        var open = 0
        val parts = HashSet<MessageId>()
    }

    private val leases = HashMap<Id, Lease>()

    fun open(fileId: Id) {
        synchronized(leases) { leases.getOrPut(fileId, ::Lease).open++ }
    }

    /** Whether a reader of [fileId] is open now: the cache keeps its pieces until it closes. */
    fun isOpen(fileId: Id): Boolean = synchronized(leases) { leases.containsKey(fileId) }

    /**
     * A reader of [fileId] closed, having read [parts]. The last one lets go of what all of them read,
     * through [release], in the background; a reader opening meanwhile takes over what is left.
     */
    fun close(fileId: Id, parts: Set<MessageId>, release: suspend (MessageId) -> Unit) {
        val done = synchronized(leases) {
            val lease = leases[fileId] ?: return
            lease.parts += parts
            if (--lease.open > 0) return
            leases.remove(fileId)
            lease.parts.toList()
        }
        scope.launch {
            for ((i, part) in done.withIndex()) {
                val reopened = synchronized(leases) {
                    leases[fileId]?.also { it.parts += done.subList(i, done.size) } != null
                }
                if (reopened) return@launch
                runCatching { release(part) }
            }
        }
    }
}
