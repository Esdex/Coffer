package me.esdex.coffer.core.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One account's Saved Messages in memory, shared by any number of fake devices ([device]).
 * It follows what the TDLib backend does, which the shared contract suite checks on both.
 *
 * @param pageCap the most messages one history call returns, to make callers that expect full pages fail.
 */
class FakeCloud(private val pageCap: Int = Int.MAX_VALUE) {
    private val lock = Any()
    private val messages = TreeMap<MessageId, Entry>()
    private val devices = CopyOnWriteArrayList<FakeStorageBackend>()
    private var lastServerId = 0L
    private var nextFailure: StorageException? = null

    private class Entry(val message: StoredMessage, val bytes: ByteArray?)

    /** Runs inside every upload and download while it holds its transfer slot. Tests suspend here. */
    @Volatile
    var onTransfer: suspend () -> Unit = {}

    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()

    /** The most transfers that ever ran at the same time. */
    val maxConcurrentTransfers: Int get() = maxActive.get()

    fun device(): FakeStorageBackend = FakeStorageBackend(this).also { devices += it }

    /** The next call on any device throws [error] instead of doing anything. */
    fun failNextCall(error: StorageException) = synchronized(lock) { nextFailure = error }

    /** Something the user posts in the official app; every device sees it. */
    fun postFromOutside(content: MessageContent): StoredMessage = commit(content, bytes = null)

    /** A deletion in the official app; every device sees it. */
    fun deleteFromOutside(ids: Collection<MessageId>) = delete(ids)

    fun allMessages(): List<StoredMessage> = synchronized(lock) { messages.values.map { it.message } }

    fun blobBytes(id: MessageId): ByteArray =
        synchronized(lock) { messages[id]?.bytes?.copyOf() } ?: throw StorageException("no blob $id")

    internal fun checkFailure() = synchronized(lock) {
        nextFailure?.let { nextFailure = null; throw it }
    }

    internal suspend fun <T> transfer(block: () -> T): T {
        val now = active.incrementAndGet()
        maxActive.accumulateAndGet(now, ::maxOf)
        try {
            onTransfer()
            return block()
        } finally {
            active.decrementAndGet()
        }
    }

    internal fun commit(content: MessageContent, bytes: ByteArray?): StoredMessage = synchronized(lock) {
        // Real ids are server ids shifted left by 20 bits; gaps keep callers from assuming id + 1.
        val message = StoredMessage(MessageId(++lastServerId shl 20), content, isPinned = false)
        messages[message.id] = Entry(message, bytes)
        broadcast(StorageEvent.NewMessage(message))
        message
    }

    internal fun message(id: MessageId): StoredMessage? = synchronized(lock) { messages[id]?.message }

    internal fun historyAfter(after: MessageId, limit: Int): List<StoredMessage> = synchronized(lock) {
        messages.tailMap(after, false).values.take(minOf(limit, pageCap)).map { it.message }
    }

    internal fun historyBefore(before: MessageId?, limit: Int): List<StoredMessage> = synchronized(lock) {
        val older = if (before == null) messages else messages.headMap(before, false)
        older.descendingMap().values.take(minOf(limit, pageCap)).map { it.message }
    }

    internal fun pinned(): List<StoredMessage> = synchronized(lock) {
        messages.descendingMap().values.map { it.message }.filter { it.isPinned }
    }

    internal fun setPinned(id: MessageId, pinned: Boolean) = synchronized(lock) {
        val entry = messages[id] ?: throw StorageException("message $id not found")
        messages[id] = Entry(entry.message.copy(isPinned = pinned), entry.bytes)
    }

    internal fun delete(ids: Collection<MessageId>) = synchronized(lock) {
        val deleted = ids.filterTo(mutableSetOf()) { messages.remove(it) != null }
        if (deleted.isNotEmpty()) broadcast(StorageEvent.MessagesDeleted(deleted))
    }

    internal fun blob(id: MessageId): Pair<StoredMessage, ByteArray> = synchronized(lock) {
        val entry = messages[id] ?: throw StorageException("message $id not found")
        entry.message to (entry.bytes ?: throw StorageException("message $id is not a blob"))
    }

    // Under the lock, so every device sees events in id order.
    private fun broadcast(event: StorageEvent) = devices.forEach { it.deliver(event) }
}

/** One device's view of a [FakeCloud]. */
class FakeStorageBackend internal constructor(private val cloud: FakeCloud) : StorageBackend {
    private val limiter = TransferLimiter()
    private val eventFlow = MutableSharedFlow<StorageEvent>(extraBufferCapacity = Int.MAX_VALUE)

    override val events: Flow<StorageEvent> = eventFlow.asSharedFlow()

    internal fun deliver(event: StorageEvent) {
        check(eventFlow.tryEmit(event))
    }

    override suspend fun sendText(text: String): StoredMessage {
        cloud.checkFailure()
        if (text.isBlank()) throw StorageException("message text is empty")
        if (text.length > StorageBackend.MAX_TEXT) throw StorageException("message text is too long")
        return cloud.commit(MessageContent.Text(text), bytes = null)
    }

    override suspend fun sendBlob(file: File, caption: String, onProgress: (Progress) -> Unit): StoredMessage {
        cloud.checkFailure()
        require(file.isFile && file.length() > 0) { "blob file must exist and not be empty" }
        if (caption.length > StorageBackend.MAX_CAPTION) throw StorageException("caption is too long")
        val bytes = file.readBytes()
        limiter.transfer {
            cloud.transfer {
                onProgress(Progress(0, bytes.size.toLong()))
                onProgress(Progress(bytes.size.toLong(), bytes.size.toLong()))
            }
        }
        // The id comes when the upload is done, as on the server: a later small send can overtake a big upload.
        return cloud.commit(MessageContent.Blob(caption, file.name, bytes.size.toLong()), bytes)
    }

    override suspend fun message(id: MessageId): StoredMessage? {
        cloud.checkFailure()
        return cloud.message(id)
    }

    override suspend fun historyAfter(after: MessageId, limit: Int): List<StoredMessage> {
        require(limit in 1..StorageBackend.MAX_PAGE) { "limit must be in 1..${StorageBackend.MAX_PAGE}" }
        cloud.checkFailure()
        return cloud.historyAfter(after, limit)
    }

    override suspend fun historyBefore(before: MessageId?, limit: Int): List<StoredMessage> {
        require(limit in 1..StorageBackend.MAX_PAGE) { "limit must be in 1..${StorageBackend.MAX_PAGE}" }
        cloud.checkFailure()
        return cloud.historyBefore(before, limit)
    }

    override suspend fun pinnedMessages(): List<StoredMessage> {
        cloud.checkFailure()
        return cloud.pinned()
    }

    override suspend fun pin(id: MessageId) {
        cloud.checkFailure()
        cloud.setPinned(id, true)
    }

    override suspend fun unpin(id: MessageId) {
        cloud.checkFailure()
        cloud.setPinned(id, false)
    }

    override suspend fun delete(ids: Collection<MessageId>) {
        cloud.checkFailure()
        cloud.delete(ids)
    }

    override suspend fun openBlob(id: MessageId, offset: Long, length: Long, onProgress: (Progress) -> Unit): InputStream {
        cloud.checkFailure()
        val (message, bytes) = cloud.blob(id)
        val range = blobRange((message.content as MessageContent.Blob).size, offset, length)
        limiter.transfer {
            cloud.transfer {
                onProgress(Progress(0, range.last - range.first + 1))
                onProgress(Progress(range.last - range.first + 1, range.last - range.first + 1))
            }
        }
        return ByteArrayInputStream(bytes, range.first.toInt(), (range.last - range.first + 1).toInt())
    }

    override suspend fun releaseBlob(id: MessageId) {
        cloud.checkFailure()
    }
}

/**
 * The byte range [StorageBackend.openBlob] asks for, checked against the blob size.
 * An empty range is allowed only at the very end.
 */
fun blobRange(size: Long, offset: Long, length: Long): LongRange {
    require(offset in 0..size) { "offset $offset outside a blob of $size bytes" }
    val end = if (length == StorageBackend.WHOLE) size else offset + length
    require(length == StorageBackend.WHOLE || length >= 0) { "negative length" }
    require(end <= size) { "range $offset+$length outside a blob of $size bytes" }
    return offset until end
}
