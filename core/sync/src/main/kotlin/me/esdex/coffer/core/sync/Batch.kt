package me.esdex.coffer.core.sync

import kotlinx.coroutines.delay
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.FloodWaitException

/**
 * Runs [action] on each of [items] in turn: one action on many files or folders at once. A FLOOD_WAIT
 * is waited out and the same item tried again (SPEC §6: honest FLOOD_WAIT), so a long selection is not
 * left half done by Telegram asking for a pause; any other error ends the batch and is thrown. Every
 * action here may run twice safely: what it already did shows in the index, or repeats harmlessly.
 */
suspend fun <T> eachWaitingOutFloods(items: Iterable<T>, onWait: (seconds: Int) -> Unit = {}, action: suspend (T) -> Unit) {
    for (item in items) waitingOutFloods(onWait) { action(item) }
}

/** One action of a batch, tried again after each FLOOD_WAIT until it goes through (see [eachWaitingOutFloods]). */
suspend fun <R> waitingOutFloods(onWait: (seconds: Int) -> Unit = {}, action: suspend () -> R): R {
    while (true) {
        try {
            return action()
        } catch (e: FloodWaitException) {
            onWait(e.seconds)
            delay(e.seconds * 1000L)
        }
    }
}

/** True if all of [movingFolders] may go into [destination]: none into itself or a folder below it. */
fun VaultTree.canTake(destination: Id?, movingFolders: Collection<Id>): Boolean =
    destination == null || movingFolders.none { isWithin(destination, it) }
