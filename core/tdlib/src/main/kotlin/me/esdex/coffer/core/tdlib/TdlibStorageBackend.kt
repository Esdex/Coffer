package me.esdex.coffer.core.tdlib

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.Progress
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageEvent
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.storage.StoredMessage
import me.esdex.coffer.core.storage.TransferLimiter
import me.esdex.coffer.core.storage.blobRange
import org.drinkless.tdlib.TdApi
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Saved Messages through TDLib. Created by [TelegramSession.storage]. */
class TdlibStorageBackend internal constructor(
    private val session: TelegramSession,
    private val chatId: Long,
) : StorageBackend {
    private val limiter = TransferLimiter()

    // Send results arrive as updates, possibly before the sendMessage call returns the temporary id.
    // Results nobody waits for yet are parked in `arrived`.
    private val sendLock = Any()
    private val waiting = HashMap<Long, CompletableDeferred<TdApi.Object>>()
    private val arrived = HashMap<Long, TdApi.Object>()

    private val fileProgress = ConcurrentHashMap<Int, (TdApi.File) -> Unit>()

    // TDLib keeps one download per file, and a request for another range ends the running one early.
    private val fileLocks = ConcurrentHashMap<Int, Mutex>()

    init {
        session.addListener(::onUpdate)
    }

    override val events: Flow<StorageEvent> = session.updates.mapNotNull { update ->
        when (update) {
            // Messages from other devices arrive as new messages; this device's own as send results.
            is TdApi.UpdateNewMessage -> update.message.takeIf { it.isLogged(chatId) }?.let { StorageEvent.NewMessage(it.toStored()) }
            is TdApi.UpdateMessageSendSucceeded ->
                update.message.takeIf { it.isLogged(chatId) }?.let { StorageEvent.NewMessage(it.toStored()) }
            is TdApi.UpdateDeleteMessages -> update.toEvent(chatId)
            else -> null
        }
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateMessageSendSucceeded -> settle(update.message.chatId, update.oldMessageId, update)
            is TdApi.UpdateMessageSendFailed -> settle(update.message.chatId, update.oldMessageId, update)
            is TdApi.UpdateFile -> fileProgress[update.file.id]?.invoke(update.file)
            else -> Unit
        }
    }

    private fun settle(chat: Long, pendingId: Long, result: TdApi.Object) {
        if (chat != chatId) return
        synchronized(sendLock) {
            waiting.remove(pendingId)?.complete(result) ?: arrived.put(pendingId, result)
        }
    }

    private fun outcomeOf(pendingId: Long): CompletableDeferred<TdApi.Object> = synchronized(sendLock) {
        arrived.remove(pendingId)?.let { CompletableDeferred(it) }
            ?: CompletableDeferred<TdApi.Object>().also { waiting[pendingId] = it }
    }

    /** Sends and waits until the server has the message, so the result carries its final id. */
    private suspend fun send(
        content: TdApi.InputMessageContent,
        onPending: (TdApi.Message) -> Unit = {},
    ): StoredMessage {
        val kind = if (content is TdApi.InputMessageText) "text" else "blob"
        val pending = session.send(TdApi.SendMessage(chatId, null, null, null, null, content))
        if (pending.sendingState == null) return pending.toStored()
        val started = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "send $kind ${pending.id} pending")
        val outcome = outcomeOf(pending.id)
        try {
            onPending(pending)
            return when (val result = outcome.await()) {
                is TdApi.UpdateMessageSendSucceeded -> {
                    Log.i(TAG, "send $kind ${pending.id} sent as ${result.message.id} after ${elapsed()} ms")
                    result.message.toStored()
                }
                is TdApi.UpdateMessageSendFailed -> {
                    Log.w(TAG, "send $kind ${pending.id} failed with ${result.error.code} after ${elapsed()} ms")
                    throw result.error.toException()
                }
                else -> error("unexpected send result")
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "send $kind ${pending.id} cancelled after ${elapsed()} ms")
            synchronized(sendLock) { waiting.remove(pending.id) }
            // Deleting a message that is still being sent stops its upload; it never reaches the server.
            withContext(NonCancellable) {
                runCatching { session.send(TdApi.DeleteMessages(chatId, longArrayOf(pending.id), true)) }
            }
            throw e
        }
    }

    override suspend fun sendText(text: String): StoredMessage = send(
        TdApi.InputMessageText(
            TdApi.FormattedText(text, emptyArray()),
            TdApi.LinkPreviewOptions(true, "", false, false, false),
            false,
        ),
    )

    override suspend fun sendBlob(file: File, caption: String, onProgress: (Progress) -> Unit): StoredMessage {
        require(file.isFile && file.length() > 0) { "blob file must exist and not be empty" }
        // As a document with content type detection off: Telegram must not treat it as media (SPEC §4.2).
        val content = TdApi.InputMessageDocument(
            TdApi.InputDocument(TdApi.InputFileLocal(file.absolutePath), null, true),
            TdApi.FormattedText(caption, emptyArray()),
        )
        return limiter.transfer {
            var fileId: Int? = null
            try {
                send(content) { pending ->
                    val document = (pending.content as? TdApi.MessageDocument)?.document?.document ?: return@send
                    fileId = document.id
                    fileProgress[document.id] = { f ->
                        onProgress(Progress(f.remote.uploadedSize, maxOf(f.size, f.expectedSize)))
                    }
                }
            } finally {
                fileId?.let(fileProgress::remove)
            }
        }
    }

    private suspend fun tdMessage(id: MessageId): TdApi.Message? = try {
        session.send(TdApi.GetMessage(chatId, id.value)).takeIf { it.isLogged(chatId) }
    } catch (e: TdlibException) {
        if (e.code == 404) null else throw e
    }

    override suspend fun message(id: MessageId): StoredMessage? = tdMessage(id)?.toStored()

    override suspend fun historyAfter(after: MessageId, limit: Int): List<StoredMessage> {
        require(limit in 1..StorageBackend.MAX_PAGE) { "limit must be in 1..${StorageBackend.MAX_PAGE}" }
        require(after.value > 0) { "after must be a message id" }
        return tdHistoryAfter(after, limit).map { it.toStored() }
    }

    private suspend fun tdHistoryAfter(after: MessageId, limit: Int): List<TdApi.Message> {
        repeat(HISTORY_ATTEMPTS) { attempt ->
            // A negative offset reads newer messages; the page also holds `after` and older ones.
            val page = session.send(TdApi.GetChatHistory(chatId, after.value, -limit, limit + 1, false)).messages
            val newer = page.filter { it.id > after.value && it.isLogged(chatId) }.sortedBy { it.id }
            if (newer.isNotEmpty()) return newer.take(limit)
            // TDLib may answer from a history it has not fully loaded. Nothing newer is only
            // believable when the chat's last message agrees.
            val last = session.send(TdApi.GetChat(chatId)).lastMessage
            if (last == null || !isServerMessageId(last.id) || last.id <= after.value) return emptyList()
            delay(HISTORY_RETRY_MILLIS * (attempt + 1))
        }
        throw StorageException("history after $after did not load")
    }

    override suspend fun historyBefore(before: MessageId?, limit: Int): List<StoredMessage> {
        require(limit in 1..StorageBackend.MAX_PAGE) { "limit must be in 1..${StorageBackend.MAX_PAGE}" }
        var from = before?.value ?: 0L
        var emptyAnswers = 0
        while (true) {
            // Offset 0 reads `from` itself and older messages; one extra for `from`.
            val older = session.send(TdApi.GetChatHistory(chatId, from, 0, limit + 1, false)).messages
                .filter { from == 0L || it.id < from }
                .sortedByDescending { it.id }
            val logged = older.filter { it.isLogged(chatId) }
            if (logged.isNotEmpty()) return logged.take(limit).map { it.toStored() }
            if (older.isNotEmpty()) {
                // Only messages that are not on the server yet: look past them.
                from = older.last().id
                continue
            }
            // An empty answer can also come from history TDLib has not loaded yet; ask again before
            // taking it as the start of the chat.
            if (++emptyAnswers == HISTORY_ATTEMPTS) return emptyList()
            delay(HISTORY_RETRY_MILLIS * emptyAnswers)
        }
    }

    /**
     * The service messages Telegram adds to the chat when something is pinned, newer than [after].
     * They are not part of the vault and can be deleted.
     */
    suspend fun pinNoticesAfter(after: MessageId): List<MessageId> {
        val notices = mutableListOf<MessageId>()
        var anchor = after
        while (true) {
            val page = tdHistoryAfter(anchor, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) return notices
            page.filter { it.content is TdApi.MessagePinMessage }.mapTo(notices) { MessageId(it.id) }
            anchor = MessageId(page.last().id)
        }
    }

    override suspend fun pinnedMessages(): List<StoredMessage> {
        val pinned = mutableListOf<StoredMessage>()
        var from = 0L
        do {
            val found = session.send(
                TdApi.SearchChatMessages(chatId, null, "", null, from, 0, 100, TdApi.SearchMessagesFilterPinned()),
            )
            found.messages.filter { it.isLogged(chatId) }.mapTo(pinned) { it.toStored() }
            from = found.nextFromMessageId
        } while (from != 0L && found.messages.isNotEmpty())
        return pinned.sortedByDescending { it.id }
    }

    override suspend fun pin(id: MessageId) {
        session.send(TdApi.PinChatMessage(chatId, id.value, true, false))
    }

    override suspend fun unpin(id: MessageId) {
        session.send(TdApi.UnpinChatMessage(chatId, id.value))
    }

    override suspend fun delete(ids: Collection<MessageId>) {
        if (ids.isEmpty()) return
        session.send(TdApi.DeleteMessages(chatId, ids.map { it.value }.toLongArray(), true))
    }

    private suspend fun blobFile(id: MessageId): TdApi.File {
        val message = tdMessage(id) ?: throw StorageException("message $id not found")
        return (message.content as? TdApi.MessageDocument)?.document?.document
            ?: throw StorageException("message $id is not a blob")
    }

    override suspend fun openBlob(
        id: MessageId,
        offset: Long,
        length: Long,
        onProgress: (Progress) -> Unit,
    ): InputStream {
        val file = blobFile(id)
        val range = blobRange(file.size, offset, length)
        val count = range.last - range.first + 1
        if (count == 0L) return ByteArrayInputStream(ByteArray(0))
        val input = openMoving(onRetry = { Log.i(TAG, "download of file ${file.id} moved out of temp, asking again") }) {
            limiter.transfer { download(file.id, range.first, count, onProgress) }
        }
        try {
            input.channel.position(range.first)
        } catch (e: Exception) {
            input.close()
            throw e
        }
        return LimitedInputStream(input, count)
    }

    /**
     * Downloads the range and opens the file, both under the file's lock: [releaseBlob] takes it too, so
     * a DeleteFile can neither cut a download short nor take the file away before it is open. Once open,
     * a delete no longer matters: the descriptor reads on.
     */
    private suspend fun download(fileId: Int, offset: Long, count: Long, onProgress: (Progress) -> Unit): FileInputStream =
        fileLocks.getOrPut(fileId) { Mutex() }.withLock {
            fileProgress[fileId] = { f ->
                if (f.local.downloadOffset == offset) {
                    onProgress(Progress(f.local.downloadedPrefixSize.coerceAtMost(count), count))
                }
            }
            try {
                val file = session.send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, offset, count, true))
                val local = file.local
                val covered = local.isDownloadingCompleted ||
                    (local.downloadOffset <= offset && local.downloadOffset + local.downloadedPrefixSize >= offset + count)
                if (local.path.isEmpty() || !covered) throw StorageException("download stopped before the range was complete")
                FileInputStream(local.path)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runCatching { session.send(TdApi.CancelDownloadFile(fileId, false)) } }
                throw e
            } finally {
                fileProgress.remove(fileId)
            }
        }

    override suspend fun releaseBlob(id: MessageId) {
        val file = runCatching { blobFile(id) }.getOrNull() ?: return
        // Never alongside a download of the same file: TDLib drops the local copy being written, and the
        // download fails with 400 (a viewer closing a file and opening it again at once, or reading a
        // file while it is being saved).
        fileLocks.getOrPut(file.id) { Mutex() }.withLock { session.send(TdApi.DeleteFile(file.id)) }
    }

    private companion object {
        const val HISTORY_ATTEMPTS = 3
        const val HISTORY_RETRY_MILLIS = 300L

        // TDLib priorities run 1..32; vault blobs are what the user is waiting for.
        const val DOWNLOAD_PRIORITY = 16
    }
}

/**
 * Runs [open], again when the file it was told of is gone. TDLib keeps a part-downloaded file in temp/
 * and moves it out once the last piece is in, so a range that completed the file can come back with the
 * temp path just before the move. Asking again gives the new one; TDLib's own ReadFilePart retries the
 * same way.
 */
internal suspend fun openMoving(attempts: Int = OPEN_ATTEMPTS, onRetry: () -> Unit = {}, open: suspend () -> FileInputStream): FileInputStream {
    var attempt = 1
    while (true) {
        try {
            return open()
        } catch (e: FileNotFoundException) {
            if (attempt >= attempts) throw e
            onRetry()
            delay(OPEN_RETRY_MILLIS * attempt)
            attempt++
        }
    }
}

private const val OPEN_ATTEMPTS = 3
private const val OPEN_RETRY_MILLIS = 10L

/** Reads at most [remaining] bytes of [input]. */
private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, remaining))
        remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

    override fun markSupported() = false
}
