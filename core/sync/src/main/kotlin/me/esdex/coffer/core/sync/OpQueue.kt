package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.index.OpMessage
import me.esdex.coffer.core.index.PendingOp
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import java.util.UUID

/** What a batch of queued operations does, for the Transfers screen. */
enum class OpKind { CREATE_FOLDERS, RENAME, MOVE, DELETE }

/**
 * Operations on their way to Telegram (SPEC §4.3), kept in the index so they outlive the app being
 * closed or killed, and sent in the order they were made by the transfer queue, which shows them.
 *
 * Telegram lets about 20 text messages into one chat and then asks for a pause of minutes, however slowly
 * they come (measured on 2026-11-19: 17 in 2.3 s, then FLOOD_WAIT 138 s; 20 at one a second, then 283 s;
 * 20 at one every 3 s, then 238 s). So the pause is waited out, and shown, rather than paced away. Before
 * this queue, a folder of folders brought in from the phone stalled for minutes with nothing on screen,
 * and the rest of it was lost if the app was closed meanwhile. Packs (SPEC §4.6) make that pause rare:
 * one message holds 8 to some 50 operations.
 */
class OpQueue(private val index: VaultIndex) {

    /** Queues [messages], encrypted operations, as one batch of [kind]. */
    suspend fun enqueue(kind: OpKind, messages: List<OpMessage>) {
        if (messages.isEmpty()) return
        index.putOps(UUID.randomUUID().toString(), kind.name, messages)
    }

    /** A file to delete once a key can seal it into a pack with the others held (see [sealHeld]). */
    suspend fun hold(fileId: Id, parts: List<MessageId>) {
        index.putOps(HELD_BATCH, OpKind.DELETE.name, listOf(OpMessage(HELD, 1, deletedFiles = listOf(fileId), parts = parts)))
    }

    /**
     * The held deletes, as few packs as hold them, at the end of the queue; files gone meanwhile are left
     * out. Queued before the held rows go, so a crash in between deletes twice, never not at all.
     *
     * Only once no upload that replaces a file is left but those [failed]: sealed as each upload ended,
     * 150 files replaced at once went as packs of one or two and ran into Telegram's pause (2026-11-19).
     * True if something was queued to send.
     */
    suspend fun sealHeld(keys: VaultKeys, failed: Set<String> = emptySet(), clock: () -> Long): Boolean {
        val held = index.ops().filter { it.caption == HELD }
        if (held.isEmpty()) return false
        if (index.uploads().any { it.replaces != null && it.uploadId !in failed }) return false
        val partsOf = held.flatMap { row -> row.deletedFiles.map { it to row.parts } }.toMap()
        val files = partsOf.keys.filter { index.file(it) != null }
        val ops = files.map { Op.FileDelete(Id.random(keys.sodium), clock(), it) }
        val messages = keys.meta.packOps(ops).map { pack ->
            val ids = pack.map { (it as Op.FileDelete).fileId }
            OpMessage(keys.meta.encodeOpPack(pack), pack.size, deletedFiles = ids, parts = ids.flatMap { partsOf.getValue(it) })
        }
        enqueue(OpKind.DELETE, messages)
        for (row in held) index.removeOp(row.seq)
        return messages.isNotEmpty()
    }

    /** What is queued and can go now, oldest first; held deletes wait for a key. */
    suspend fun sendable(): List<PendingOp> = index.ops().filter { it.caption != HELD }

    /**
     * Sends every queued operation, oldest first, [pause] between two. Each leaves the queue once
     * Telegram has it: sent twice after a crash in between, the second is the same op_id and is not
     * applied again (audit 1, M4). A FLOOD_WAIT or any other failure is thrown with the queue as it is,
     * for the caller to wait out and call again. [onSent] gets the message sent and what is left.
     *
     * A message of deletes takes the parts of its files with it once it is in Telegram, and only then
     * (SPEC §5.3); its files count as deleted from that moment, so their parts going is not damage. A
     * message already sent is not sent again when only its parts were left.
     */
    suspend fun drain(storage: StorageBackend, pause: suspend () -> Unit, onSent: (sent: PendingOp, left: List<PendingOp>) -> Unit = { _, _ -> }) {
        while (true) {
            val next = index.ops().firstOrNull { it.caption != HELD } ?: return
            if (!next.sent) {
                storage.sendText(next.caption)
                index.markOpSent(next.seq)
            }
            for (fileId in next.deletedFiles) index.markDeleted(fileId)
            for (slice in next.parts.chunked(StorageBackend.MAX_DELETE)) storage.delete(slice)
            index.removeOp(next.seq)
            val left = index.ops().filter { it.caption != HELD }
            onSent(next, left)
            if (left.isEmpty()) return
            pause()
        }
    }

    companion object {
        // A held row has no caption yet: nothing in it can be sent.
        private const val HELD = ""
        private const val HELD_BATCH = "held-deletes"

        /** Between two operations: a breath, not a pace. Slower did not put Telegram's pause off. */
        const val PACE_MILLIS = 1_000L
    }
}
