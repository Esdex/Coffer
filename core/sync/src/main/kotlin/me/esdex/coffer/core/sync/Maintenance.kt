package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
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
    /**
     * The messages of the vault's headers this device knows. The first header is usually the vault's first
     * message, so it marks where the history begins even on a first run, before anything else does (audit
     * 2, L-d).
     */
    private val headers: () -> List<MessageId> = { emptyList() },
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
        val (history, oldestRead, read) = readAll(onProgress)
        checkComplete(oldestRead)
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

        val removed = gone + compacted
        history.filter { it.id !in removed && it.isRecord() }.minOfOrNull { it.id }?.let { index.setOldestRecord(it) }
        index.setMaintainedAt(clock())
        return Report(
            messagesRead = read,
            damaged = reconciled.damaged,
            repaired = reconciled.repaired,
            garbageDeleted = reconciled.garbage.size,
            snapshotsDeleted = reconciled.oldSnapshots.size,
            operationsCompacted = compacted.size,
        )
    }

    /**
     * TDLib can answer with an empty page before the real start of the chat; the backend takes the third
     * such answer for the start. A history cut short would make live files look damaged and let compaction
     * delete operations still in force (audit 1, M6). So every message the vault is known to have below
     * where the reading stopped must be gone by Telegram's own answer; one still there means the reading
     * was cut short, and nothing is marked or deleted. The vault's headers count too: with nothing else
     * known below a cut, as on a first run, the first header still is (audit 2, L-d).
     */
    private suspend fun checkComplete(oldestRead: MessageId?) {
        val known = index.allParts().values.flatMap { it.values } + index.snapshots().map { it.messageId } +
            listOfNotNull(index.oldestRecord()) + headers()
        for (id in known.filter { oldestRead == null || it < oldestRead }.distinct().sorted()) {
            if (storage.message(id) != null) throw StorageException("the history of Saved Messages was read only in part")
        }
    }

    private fun StoredMessage.isRecord(): Boolean = when (val content = content) {
        is MessageContent.Blob -> Captions.parse(content.caption) != null
        is MessageContent.Text -> Captions.parse(content.text) != null
        MessageContent.Other -> false
    }

    /** True if the last run was a week or more ago, or there was none. */
    suspend fun isDue(): Boolean {
        val last = index.maintainedAt() ?: return true
        return clock() - last >= WEEK_MILLIS
    }

    /**
     * Coffer's records in Saved Messages, oldest first, and the oldest message read. The user's own
     * messages are not kept: anyone in the account can post a hundred thousand long ones, and holding them
     * all ran out of memory at every unlock while the weekly run was due (audit 2, L-g).
     */
    private suspend fun readAll(onProgress: (Int) -> Unit): Triple<List<StoredMessage>, MessageId?, Int> {
        val newestFirst = mutableListOf<StoredMessage>()
        var before: MessageId? = null
        var read = 0
        while (true) {
            val page = storage.historyBefore(before, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) break
            newestFirst += page.filter { it.isRecord() }
            read += page.size
            onProgress(read)
            before = page.last().id
        }
        return Triple(newestFirst.asReversed(), before, read)
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
