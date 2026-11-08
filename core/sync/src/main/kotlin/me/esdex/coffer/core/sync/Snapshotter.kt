package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.IndexPayload
import me.esdex.coffer.core.index.KnownSnapshot
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
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
        val caption = keys.meta.encodeIndex(IndexPayload(snapshotId, pending.state.lastAppliedMessageId, clock(), sealed.plainSize))
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
        val old = index.snapshots().sortedByDescending { it.messageId }.drop(KEEP)
        if (old.isEmpty()) return
        storage.delete(old.map { it.messageId })
        for (snapshot in old) index.removeSnapshot(snapshot.messageId)
    }

    companion object {
        /** SPEC §4.4: the last 3 are kept. */
        const val KEEP = 3
    }
}
