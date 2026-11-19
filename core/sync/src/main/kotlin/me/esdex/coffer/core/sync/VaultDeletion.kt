package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StoredMessage

/**
 * Deletes a vault from Saved Messages (SPEC §5.9): every message the vault's own keys authenticate (the
 * parts of its files, its operations, its index snapshots), and then its headers, those with its
 * [masterId]. Nothing else is touched: the user's own messages, and records of other vaults, which these
 * keys cannot open, stay.
 *
 * The headers go last, so a deletion cut short leaves a vault that still opens, its files damaged, and
 * can simply be run again. With the headers gone first, what was left would belong to no vault anyone
 * could open, and no deletion could find it.
 */
class VaultDeletion(private val storage: StorageBackend, private val keys: VaultKeys, private val masterId: Bytes) {

    /** What belongs to the vault: its [content] and its [headers], each oldest first. */
    data class Selection(val content: List<MessageId>, val headers: List<MessageId>) {
        val total: Int get() = content.size + headers.size
    }

    /**
     * Reads the whole of Saved Messages, then deletes what is the vault's. [onRead] gets the messages read
     * so far, [onDeleted] how many of the vault's are gone and of how many; a FLOOD_WAIT is waited out
     * (SPEC §6), and [onWait] says for how long. Any other error ends the run and is thrown.
     */
    suspend fun run(
        onRead: (Int) -> Unit = {},
        onDeleted: (done: Int, total: Int) -> Unit = { _, _ -> },
        onWait: (seconds: Int) -> Unit = {},
    ): Selection {
        val all = readAll(onRead)
        var newest = all.maxOfOrNull { it.id }
        val first = select(all)
        val content = first.content.toMutableList()
        val headers = first.headers.toMutableList()
        var done = 0
        suspend fun deleteAll(ids: List<MessageId>) {
            for (slice in ids.chunked(SLICE)) {
                waitingOutFloods(onWait) { storage.delete(slice) }
                done += slice.size
                onDeleted(done, content.size + headers.size)
            }
        }

        /** What came in after the newest message read, the vault's part of it: sent meanwhile. */
        suspend fun late(): Selection {
            val after = newest?.let { readAfter(it) }.orEmpty()
            newest = after.maxOfOrNull { it.id } ?: newest
            return select(after)
        }

        onDeleted(0, first.total)
        deleteAll(first.content)
        // Devices still open on the vault go on sending while it goes: a snapshot of the whole file list,
        // an upload. Left behind, they would outlive the deletion (audit 2, L-e). Read on until a pass finds
        // nothing more of it, then the headers, and once more after them.
        for (pass in 0 until LATE_PASSES) {
            val more = late()
            if (more.total == 0) break
            content += more.content
            headers += more.headers
            deleteAll(more.content)
        }
        deleteAll(headers)
        val last = late()
        content += last.content
        headers += last.headers
        deleteAll(last.content + last.headers)
        return Selection(content.sorted(), headers.sorted())
    }

    private fun StoredMessage.mayBeRecord(): Boolean = when (val c = content) {
        is MessageContent.Blob -> Captions.parse(c.caption) != null
        is MessageContent.Text -> Captions.parse(c.text) != null
        MessageContent.Other -> false
    }

    private suspend fun readAfter(after: MessageId): List<StoredMessage> {
        val messages = mutableListOf<StoredMessage>()
        var from = after
        while (true) {
            val page = storage.historyAfter(from, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) break
            messages += page
            from = page.last().id
        }
        return messages
    }

    /** The vault's messages among [messages]: what its keys open, and its own headers. */
    fun select(messages: Iterable<StoredMessage>): Selection {
        val content = mutableListOf<MessageId>()
        val headers = mutableListOf<MessageId>()
        for (message in messages) {
            when (val c = message.content) {
                is MessageContent.Blob -> if (opensBlob(c.caption)) content += message.id
                is MessageContent.Text -> when {
                    isOwnHeader(c.text) -> headers += message.id
                    opensOp(c.text) -> content += message.id
                }
                MessageContent.Other -> Unit
            }
        }
        return Selection(content.sorted(), headers.sorted())
    }

    // A record that parses but does not open with these keys is another vault's, or not Coffer's at all.
    private fun opensBlob(caption: String): Boolean {
        val record = Captions.parse(caption) ?: return false
        return runCatching {
            when (record.kind) {
                RecordKind.BLOB -> keys.meta.decodeBlob(record) != null
                RecordKind.INDEX -> keys.meta.decodeIndex(record) != null
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun opensOp(text: String): Boolean {
        val record = Captions.parse(text) ?: return false
        return runCatching { keys.meta.decodeOps(record) != null }.getOrDefault(false)
    }

    // A header carries no key of its own to check: it is this vault's by its master_id.
    private fun isOwnHeader(text: String): Boolean {
        val record = Captions.parse(text)?.takeIf { it.kind == RecordKind.VAULT } ?: return false
        return VaultHeaderCodec.decode(record)?.masterId == masterId
    }

    private suspend fun readAll(onRead: (Int) -> Unit): List<StoredMessage> {
        val messages = mutableListOf<StoredMessage>()
        var before: MessageId? = null
        var read = 0
        while (true) {
            val page = storage.historyBefore(before, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) break
            // Only what could be the vault's: the user's own messages, as many as anyone posts, are not kept (audit 2, L-g).
            messages += page.filter { it.mayBeRecord() }
            read += page.size
            onRead(read)
            before = page.last().id
        }
        return messages
    }

    private companion object {
        /** One request per hundred messages, as compaction deletes (SPEC §5.6). */
        const val SLICE = 100

        /** Passes for what came in meanwhile; each takes as long as a few deletes, so devices rarely need more. */
        const val LATE_PASSES = 5
    }
}
