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
        val selection = select(readAll(onRead))
        var done = 0
        onDeleted(0, selection.total)
        for (slice in selection.content.chunked(SLICE) + selection.headers.chunked(SLICE)) {
            waitingOutFloods(onWait) { storage.delete(slice) }
            done += slice.size
            onDeleted(done, selection.total)
        }
        return selection
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
        val record = Captions.parse(text)?.takeIf { it.kind == RecordKind.OP } ?: return false
        return runCatching { keys.meta.decodeOp(record) != null }.getOrDefault(false)
    }

    // A header carries no key of its own to check: it is this vault's by its master_id.
    private fun isOwnHeader(text: String): Boolean {
        val record = Captions.parse(text)?.takeIf { it.kind == RecordKind.VAULT } ?: return false
        return VaultHeaderCodec.decode(record)?.masterId == masterId
    }

    private suspend fun readAll(onRead: (Int) -> Unit): List<StoredMessage> {
        val messages = mutableListOf<StoredMessage>()
        var before: MessageId? = null
        while (true) {
            val page = storage.historyBefore(before, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) break
            messages += page
            onRead(messages.size)
            before = page.last().id
        }
        return messages
    }

    private companion object {
        /** One request per hundred messages, as compaction deletes (SPEC §5.6). */
        const val SLICE = 100
    }
}
