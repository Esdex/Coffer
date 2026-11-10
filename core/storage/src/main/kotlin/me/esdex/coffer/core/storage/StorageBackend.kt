package me.esdex.coffer.core.storage

import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream

/**
 * Saved Messages as a storage medium (SPEC §2). Everything above this interface works the same on
 * [FakeStorageBackend] in JVM tests and on TDLib in the app.
 *
 * Message ids are assigned by the server and only grow, so they are the global order of events (SPEC §5).
 * They are not contiguous: never compute one from another.
 */
interface StorageBackend {

    /**
     * Messages the server confirmed, this device's own sends included, and permanent deletions.
     * Hot: an event is delivered only to collectors active when it happens. Anything missed is
     * recovered from [historyAfter].
     */
    val events: Flow<StorageEvent>

    /** Sends a text message, for example a vault header or an operation. */
    suspend fun sendText(text: String): StoredMessage

    /**
     * Uploads [file] as a document with [caption]. The document is named after the file, so the file
     * must already have its final faceless name (SPEC §4.2). Holds one transfer slot while uploading.
     */
    suspend fun sendBlob(file: File, caption: String, onProgress: (Progress) -> Unit = {}): StoredMessage

    /** The message, or null if it does not exist (any more). */
    suspend fun message(id: MessageId): StoredMessage?

    /**
     * Up to [limit] messages with ids strictly greater than [after], oldest first. [after] need not
     * exist any more. A non-empty page may be shorter than [limit]; only an empty page means the end.
     */
    suspend fun historyAfter(after: MessageId, limit: Int): List<StoredMessage>

    /**
     * Up to [limit] messages with ids strictly less than [before], newest first; null starts at the newest.
     * A non-empty page may be shorter than [limit]; only an empty page means the beginning was reached.
     */
    suspend fun historyBefore(before: MessageId?, limit: Int): List<StoredMessage>

    /** Pinned messages, newest first. */
    suspend fun pinnedMessages(): List<StoredMessage>

    suspend fun pin(id: MessageId)

    suspend fun unpin(id: MessageId)

    /** Deletes for good, on every device. Unknown ids are ignored. */
    suspend fun delete(ids: Collection<MessageId>)

    /**
     * Makes [length] bytes of the blob from [offset] available locally (whole rest of the blob if
     * [length] is [WHOLE]) and opens them for reading. Holds one transfer slot while downloading,
     * not while the caller reads. Call [releaseBlob] when done to drop the local copy.
     */
    suspend fun openBlob(
        id: MessageId,
        offset: Long = 0,
        length: Long = WHOLE,
        onProgress: (Progress) -> Unit = {},
    ): InputStream

    /** Drops whatever local copy [openBlob] left behind. */
    suspend fun releaseBlob(id: MessageId)

    /** How much of the blob [openBlob] has left on the phone and [releaseBlob] has not dropped; 0 for none. */
    suspend fun localBytes(id: MessageId): Long

    companion object {
        const val WHOLE = -1L

        /** Largest page the history calls accept; TDLib allows no more for forward reads. */
        const val MAX_PAGE = 99

        /** Telegram limits without Premium, in UTF-16 units. Longer text or captions are rejected. */
        const val MAX_CAPTION = 1024
        const val MAX_TEXT = 4096
    }
}

@JvmInline
value class MessageId(val value: Long) : Comparable<MessageId> {
    override fun compareTo(other: MessageId): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
}

/** [date] is when the server took the message, unix time in seconds (SPEC §5.6, §5.7 count ages with it). */
data class StoredMessage(val id: MessageId, val content: MessageContent, val isPinned: Boolean, val date: Long)

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent

    data class Blob(val caption: String, val fileName: String, val size: Long) : MessageContent

    /** Anything else in Saved Messages: the user's own photos, stickers, service messages. */
    data object Other : MessageContent
}

sealed interface StorageEvent {
    data class NewMessage(val message: StoredMessage) : StorageEvent

    /**
     * Messages deleted on the server. Never sent for messages TDLib merely dropped from its local
     * cache, which would otherwise look like a blob deleted outside the app (SPEC §5.1).
     */
    data class MessagesDeleted(val ids: Set<MessageId>) : StorageEvent
}

data class Progress(val done: Long, val total: Long)

open class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** FLOOD_WAIT: wait [seconds] before the next attempt, and never retry in a loop (SPEC §6). */
class FloodWaitException(val seconds: Int) : StorageException("flood wait, retry after $seconds s")
