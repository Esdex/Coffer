package me.esdex.coffer.core.sync

import me.esdex.coffer.core.index.OpMessage
import me.esdex.coffer.core.index.PendingOp
import me.esdex.coffer.core.index.VaultIndex
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
            val next = index.ops().firstOrNull() ?: return
            if (!next.sent) {
                storage.sendText(next.caption)
                index.markOpSent(next.seq)
            }
            for (fileId in next.deletedFiles) index.markDeleted(fileId)
            for (slice in next.parts.chunked(StorageBackend.MAX_DELETE)) storage.delete(slice)
            index.removeOp(next.seq)
            val left = index.ops()
            onSent(next, left)
            if (left.isEmpty()) return
            pause()
        }
    }

    companion object {
        /** Between two operations: a breath, not a pace. Slower did not put Telegram's pause off. */
        const val PACE_MILLIS = 1_000L
    }
}
