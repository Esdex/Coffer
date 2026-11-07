package me.esdex.coffer.core.storage

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * At most [MAX_TRANSFERS] uploads and downloads at once, counted together (SPEC §6).
 * One per backend: the app has a single backend, so this is the whole app's budget.
 */
class TransferLimiter(permits: Int = MAX_TRANSFERS) {
    private val semaphore = Semaphore(permits)

    suspend fun <T> transfer(block: suspend () -> T): T = semaphore.withPermit { block() }

    companion object {
        const val MAX_TRANSFERS = 3
    }
}
