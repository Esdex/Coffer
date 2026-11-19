package me.esdex.coffer.core.sync

import kotlinx.coroutines.delay
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.FloodWaitException

/**
 * Runs [action] again after each FLOOD_WAIT until it goes through (SPEC §6: honest FLOOD_WAIT); any
 * other error is thrown. [action] must be safe to run twice.
 */
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
