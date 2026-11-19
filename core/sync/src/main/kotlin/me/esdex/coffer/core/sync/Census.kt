package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StoredMessage

/**
 * What Saved Messages holds, by kind of message (SPEC §4), for the Storage screen: the parts of files,
 * the operations, the index snapshots, the vault header, and how many messages are the user's own,
 * which Coffer leaves alone. Sizes are what Telegram stores: a document's size, a text's bytes.
 */
data class Census(val parts: Tally, val operations: Tally, val snapshots: Tally, val headers: Tally, val ownMessages: Int) {

    data class Tally(val count: Int = 0, val bytes: Long = 0) {
        operator fun plus(bytes: Long) = Tally(count + 1, this.bytes + bytes)
    }

    /** Everything Coffer keeps in Saved Messages. */
    val coffer: Tally get() = Tally(parts.count + operations.count + snapshots.count + headers.count, parts.bytes + operations.bytes + snapshots.bytes + headers.bytes)

    companion object {
        fun of(messages: Iterable<StoredMessage>): Census {
            var parts = Tally()
            var operations = Tally()
            var snapshots = Tally()
            var headers = Tally()
            var own = 0
            for (message in messages) {
                val (text, bytes) = when (val content = message.content) {
                    is MessageContent.Blob -> content.caption to content.size
                    is MessageContent.Text -> content.text to content.text.toByteArray().size.toLong()
                    MessageContent.Other -> null to 0L
                }
                when (Captions.parse(text)?.kind) {
                    RecordKind.BLOB -> parts += bytes
                    RecordKind.OP, RecordKind.OPS -> operations += bytes
                    RecordKind.INDEX -> snapshots += bytes
                    RecordKind.VAULT -> headers += bytes
                    null -> own++
                }
            }
            return Census(parts, operations, snapshots, headers, own)
        }
    }
}

/** Reads the whole of Saved Messages and counts it; nothing is changed. [onProgress] gets the messages read so far. */
suspend fun StorageBackend.census(onProgress: (Int) -> Unit = {}): Census {
    val messages = mutableListOf<StoredMessage>()
    var before: MessageId? = null
    while (true) {
        val page = historyBefore(before, StorageBackend.MAX_PAGE)
        if (page.isEmpty()) break
        messages += page
        onProgress(messages.size)
        before = page.last().id
    }
    return Census.of(messages)
}
