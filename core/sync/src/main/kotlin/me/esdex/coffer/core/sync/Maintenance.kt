package me.esdex.coffer.core.sync

import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StoredMessage

/**
 * The integrity check (SPEC §5.8) and compaction (§5.6) on one full pass over Saved Messages: weekly at
 * unlock, or when the user asks.
 */
class Maintenance(
    private val storage: StorageBackend,
    private val index: VaultIndex,
    private val indexer: Indexer,
    private val snapshotter: Snapshotter,
    private val keys: VaultKeys,
    /** Unix millis. */
    private val clock: () -> Long,
) {
    data class Report(
        val messagesRead: Int,
        val damaged: Int,
        val repaired: Int,
        val garbageDeleted: Int,
        val snapshotsDeleted: Int,
        val operationsCompacted: Int,
    )

    suspend fun run(onProgress: (Int) -> Unit = {}): Report {
        // A fresh snapshot first, so compaction can reach the most recent operations.
        snapshotter.snapshotIfNeeded()
        val history = readAll(onProgress)
        val nowSeconds = clock() / 1000
        val reconciled = indexer.reconcile(history, nowSeconds)
        val gone = (reconciled.garbage + reconciled.oldSnapshots).toSet()
        delete(gone)

        // Below the oldest snapshot kept rather than the newest (SPEC §5.6 asks only the newest): a device
        // that falls back to an older snapshot then still finds every operation after it.
        val oldestKept = index.snapshots().minOfOrNull { it.lastApplied }
        val compacted = if (oldestKept == null) {
            emptySet()
        } else {
            Compactor(storage, keys).removable(history.filter { it.id !in gone }, oldestKept, nowSeconds - COMPACT_AGE_SECONDS)
        }
        delete(compacted)

        index.setMaintainedAt(clock())
        return Report(
            messagesRead = history.size,
            damaged = reconciled.damaged,
            repaired = reconciled.repaired,
            garbageDeleted = reconciled.garbage.size,
            snapshotsDeleted = reconciled.oldSnapshots.size,
            operationsCompacted = compacted.size,
        )
    }

    /** True if the last run was a week or more ago, or there was none. */
    suspend fun isDue(): Boolean {
        val last = index.maintainedAt() ?: return true
        return clock() - last >= WEEK_MILLIS
    }

    private suspend fun readAll(onProgress: (Int) -> Unit): List<StoredMessage> {
        val newestFirst = mutableListOf<StoredMessage>()
        var before: MessageId? = null
        while (true) {
            val page = storage.historyBefore(before, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) break
            newestFirst += page
            onProgress(newestFirst.size)
            before = page.last().id
        }
        return newestFirst.asReversed()
    }

    // In slices: one request per hundred, so a large compaction does not set off FLOOD_WAIT at once.
    private suspend fun delete(ids: Collection<MessageId>) {
        for (slice in ids.sorted().chunked(DELETE_SLICE)) storage.delete(slice)
    }

    companion object {
        /** SPEC §5.6: only operations older than 30 days go. */
        const val COMPACT_AGE_SECONDS = 30 * 24 * 60 * 60L
        const val WEEK_MILLIS = 7 * 24 * 60 * 60 * 1000L
        private const val DELETE_SLICE = 100
    }
}
