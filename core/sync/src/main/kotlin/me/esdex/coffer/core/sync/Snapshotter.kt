package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.IndexPayload
import me.esdex.coffer.core.index.KnownSnapshot
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.storage.MessageContent
import java.io.File

/**
 * Sends index snapshots (SPEC §4.4) and keeps only the newest few. A snapshot is sent only when
 * something changed since the newest known one, by any device, so devices do not answer each other's
 * snapshots with more snapshots.
 */
class Snapshotter(
    private val storage: StorageBackend,
    private val index: VaultIndex,
    private val indexer: Indexer,
    private val keys: VaultKeys,
    private val tempDir: File,
    private val clock: () -> Long,
) {
    /** True if a snapshot was sent. */
    suspend fun snapshotIfNeeded(): Boolean {
        val pending = indexer.pendingSnapshot() ?: return false
        val snapshotId = Id.random(keys.sodium)
        val sealed = keys.snapshots.encode(snapshotId, pending.state)
        // Version 2 and padded always (SPEC §4.5): its size would otherwise tell how many files there are.
        val caption = keys.meta.encodeIndex(IndexPayload(snapshotId, pending.state.lastAppliedMessageId, clock(), sealed.plainSize, padded = true))
        tempDir.mkdirs()
        val temp = File(tempDir, "cfr_" + keys.sodium.randomBytes(6).joinToString("") { "%02x".format(it) } + ".bin")
        try {
            temp.writeBytes(sealed.bytes)
            val message = storage.sendBlob(temp, caption)
            indexer.snapshotSent(pending, KnownSnapshot(message.id, MessageId(pending.state.lastAppliedMessageId)))
        } finally {
            temp.delete()
        }
        prune()
        return true
    }

    private suspend fun prune() {
        val known = index.snapshots()
        // A copy of a snapshot carries its caption letter for letter; two snapshots never do, each sealed with
        // a random nonce. Copies, as anyone in the account can post over documents of its size, go first, the
        // first one sent staying: they must not take the places of other snapshots (audit 2, L-f).
        val captions = known.associateWith { snapshot ->
            try {
                (storage.message(snapshot.messageId)?.content as? MessageContent.Blob)?.caption
            } catch (_: StorageException) {
                null
            }
        }
        val copies = known.filter { captions[it] != null }.groupBy { captions[it] }.values
            .flatMap { same -> same.sortedBy { it.messageId }.drop(1) }.toSet()
        val kept = (known - copies).sortedWith(NEWEST_STATE_FIRST).take(KEEP).toSet()
        val old = known.filter { it !in kept }
        if (old.isEmpty()) return
        storage.delete(old.map { it.messageId })
        for (snapshot in old) index.removeSnapshot(snapshot.messageId)
    }

    companion object {
        /** SPEC §4.4: the last 3 are kept. */
        const val KEEP = 3

        /**
         * The order snapshots are kept in: by the state they hold, then by message id. Not by message id
         * alone: an old snapshot sent again has a new one, and must not push out the newest (audit 1, M5).
         */
        val NEWEST_STATE_FIRST: Comparator<KnownSnapshot> =
            compareByDescending<KnownSnapshot> { it.lastApplied }.thenByDescending { it.messageId }
    }
}
