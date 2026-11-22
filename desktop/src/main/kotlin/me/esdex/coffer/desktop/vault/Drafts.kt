package me.esdex.coffer.desktop.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.LocalUpload
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.OpQueue
import me.esdex.coffer.core.sync.SourceChangedException
import me.esdex.coffer.core.sync.UploadPreparer
import me.esdex.coffer.core.sync.UploadRunner
import me.esdex.coffer.core.sync.UploadSource
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.reader
import me.esdex.coffer.core.sync.transferKeys
import me.esdex.coffer.desktop.drive.DriveTree
import me.esdex.coffer.desktop.drive.OpenFile
import java.io.File
import java.net.URLConnection
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Files written on the drive (Esdex, 2026-11-22): each is a draft, a copy in [dir] on this computer, readable
 * by this user only, queued as an upload (SPEC §6) the moment it is made, so the queue outlives the app as
 * the phone's does. Once nothing has it open and it has been quiet for a moment, it goes up as the phone
 * uploads a file, and the copy is deleted. A draft that takes a file's place (an edit, or a save that renames
 * over the old file) uploads as a replacement: the old file goes once the new one is whole.
 *
 * One upload at a time; a draft opened for writing again while it goes up is taken back and goes up later.
 */
class Drafts(
    private val scope: CoroutineScope,
    private val index: VaultIndex,
    private val keys: VaultKeys,
    private val storage: suspend () -> StorageBackend,
    private val dir: File,
    private val clock: () -> Long,
    /**
     * After an upload: a name, folder or replaced file that changed while it went up goes as operations,
     * held deletes are packed, the drive redrawn. [uploaded] is what went up, [now] what the draft says now.
     */
    private val afterUpload: suspend (fileId: Id, uploaded: PendingUpload, now: Wanted) -> Unit,
    private val changed: suspend () -> Unit,
) {
    /** Where a draft is to go, as last asked: kept here too, as the runner takes its upload out of the queue. */
    class Wanted(val request: LocalUpload, val replaces: Id?)

    private class State {
        val open = AtomicInteger()
        @Volatile var quiet: Job? = null
        @Volatile var upload: Job? = null
        @Volatile var wanted: Wanted? = null
    }

    private val states = ConcurrentHashMap<String, State>()
    private val lock = Mutex()
    private val ready = Channel<String>(Channel.UNLIMITED)

    private fun state(uploadId: String) = states.getOrPut(uploadId, ::State)

    fun file(uploadId: String) = File(dir, uploadId)

    /** Uploads queued before, here or in an earlier session, go up again; one whose copy is gone is taken back. */
    suspend fun start() {
        dir.mkdirs()
        for (upload in index.uploads()) {
            if (file(upload.uploadId).exists()) ready.trySend(upload.uploadId) else discard(upload)
        }
        scope.launch { for (uploadId in ready) uploadWhenIdle(uploadId) }
    }

    /** The drafts the drive shows: where each goes, under which name, and what it holds so far. */
    suspend fun infos(): List<DriveTree.DraftInfo> = index.uploads().mapNotNull { upload ->
        val request = keys.local.openUpload(upload.uploadId, upload.sealedRequest) ?: return@mapNotNull null
        val copy = file(upload.uploadId)
        DriveTree.DraftInfo(upload.uploadId, request.folderId, request.name, copy.length(), copy.lastModified(), upload.replaces)
    }

    /** A new, empty draft named [name] in the folder [folderId], open for writing. */
    suspend fun create(folderId: Id?, name: String, replaces: Id? = null): OpenFile = lock.withLock {
        val uploadId = Id.random(keys.sodium).toByteArray().joinToString("") { "%02x".format(it) }
        dir.mkdirs()
        check(file(uploadId).createNewFile()) { "a draft's copy exists already" }
        put(uploadId, LocalUpload(file(uploadId).toURI().toString(), name, mimeOf(name), folderId), replaces = replaces)
        handle(uploadId, write = true, truncate = false)
    }.also { changed() }

    /**
     * The vault's file [fileId] open for writing, as a draft that takes its place under the same name and
     * folder: its content is copied out first, unless [truncate] would empty it anyway.
     */
    suspend fun edit(fileId: Id, truncate: Boolean): OpenFile {
        val indexed = checkNotNull(index.file(fileId)) { "the file is gone" }
        val meta = checkNotNull(keys.local.openFileMeta(fileId, indexed.sealedMeta)) { "the file does not open" }
        val draft = create(meta.folderId, meta.name, replaces = fileId)
        if (!truncate) {
            val transfer = checkNotNull(keys.transferKeys(fileId, indexed.sealedMeta)) { "the file's key does not open" }
            try {
                keys.reader(storage(), indexed, transfer).use { reader ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var at = 0L
                    while (true) {
                        val n = reader.read(at, buffer, 0, buffer.size)
                        if (n <= 0) break
                        draft.write!!(at, buffer, n)
                        at += n
                    }
                    buffer.fill(0)
                }
            } finally {
                transfer.close()
            }
        }
        return draft
    }

    /** [draft] open; for writing too if [write], emptied first if [truncate]. */
    suspend fun open(draft: DriveTree.Draft, write: Boolean, truncate: Boolean): OpenFile {
        if (write) takeBack(draft.uploadId)
        return handle(draft.uploadId, write, truncate)
    }

    /** Sets the draft's size, as truncate(2) does on a closed file. */
    suspend fun resize(draft: DriveTree.Draft, size: Long) {
        takeBack(draft.uploadId)
        FileChannel.open(file(draft.uploadId).toPath(), StandardOpenOption.WRITE).use { it.truncateOrExtend(size) }
        idle(draft.uploadId)
        changed()
    }

    /** [draft] to the folder [folderId] under [name], in the place of [replaces] if that is given. */
    suspend fun move(draft: DriveTree.Draft, folderId: Id?, name: String, replaces: Id?) {
        lock.withLock {
            val upload = index.uploads().find { it.uploadId == draft.uploadId } ?: return@withLock
            val request = keys.local.openUpload(upload.uploadId, upload.sealedRequest) ?: return@withLock
            val wanted = Wanted(request.copy(name = name, mime = mimeOf(name), folderId = folderId), replaces ?: upload.replaces)
            state(upload.uploadId).wanted = wanted
            put(upload.uploadId, wanted.request, upload.fileId, upload.captions, upload.abandonOp, wanted.replaces)
        }
        changed()
    }

    /** Gone: its upload taken back, what was sent of it deleted, its copy too. */
    suspend fun delete(draft: DriveTree.Draft) {
        states.remove(draft.uploadId)?.let { it.quiet?.cancel(); it.upload?.cancel(); it.upload?.join() }
        index.uploads().find { it.uploadId == draft.uploadId }?.let { discard(it) }
        changed()
    }

    private suspend fun discard(upload: PendingUpload) {
        withContext(NonCancellable) {
            runCatching { UploadRunner(keys.sodium, storage(), index, File(dir, TEMP)).cancel(upload) }
                .onFailure { log("taking back an upload failed (${it.javaClass.simpleName})") }
            index.removeUpload(upload.uploadId)
            file(upload.uploadId).delete()
        }
    }

    private suspend fun put(
        uploadId: String,
        request: LocalUpload,
        fileId: Id? = null,
        captions: List<String> = emptyList(),
        abandonOp: String? = null,
        replaces: Id? = null,
    ) = index.putUpload(PendingUpload(uploadId, keys.local.sealUpload(uploadId, request), fileId, captions, abandonOp, replaces))

    private fun handle(uploadId: String, write: Boolean, truncate: Boolean): OpenFile {
        val state = state(uploadId)
        state.open.incrementAndGet()
        state.quiet?.cancel()
        val options = if (write) setOf(StandardOpenOption.READ, StandardOpenOption.WRITE) else setOf(StandardOpenOption.READ)
        val channel = FileChannel.open(file(uploadId).toPath(), options)
        if (truncate) channel.truncate(0)
        return OpenFile(
            read = { position, buffer, offset, length ->
                channel.read(ByteBuffer.wrap(buffer, offset, length), position)
            },
            write = if (write) { position, buffer, length -> channel.write(ByteBuffer.wrap(buffer, 0, length), position) } else null,
            truncate = if (write) { size -> channel.truncateOrExtend(size) } else null,
            sync = if (write) { -> channel.force(false) } else null,
            onClose = {
                channel.close()
                if (state.open.decrementAndGet() == 0) idle(uploadId)
            },
        )
    }

    /** Uploaded once nothing has it open and it has been quiet for a moment: an editor saves in several steps. */
    private fun idle(uploadId: String) {
        val state = state(uploadId)
        state.quiet?.cancel()
        state.quiet = scope.launch {
            delay(QUIET_MS)
            if (state.open.get() == 0) ready.trySend(uploadId)
        }
    }

    /** A draft that goes up while it is written to again is taken back, to go up whole later. */
    private suspend fun takeBack(uploadId: String) {
        val state = state(uploadId)
        state.quiet?.cancel()
        val running = state.upload ?: return
        running.cancel()
        running.join()
    }

    private suspend fun uploadWhenIdle(uploadId: String) {
        val state = state(uploadId)
        if (state.open.get() > 0) return
        val job = scope.launch { upload(uploadId) }
        state.upload = job
        job.join()
        state.upload = null
    }

    private suspend fun upload(uploadId: String) {
        var pause = FIRST_RETRY_MS
        while (true) {
            val queued = index.uploads().find { it.uploadId == uploadId } ?: return
            val request = keys.local.openUpload(uploadId, queued.sealedRequest) ?: return discard(queued)
            val copy = file(uploadId)
            if (!copy.exists()) return discard(queued)
            val source = object : UploadSource {
                override fun open() = copy.inputStream()
            }
            try {
                val (upload, transfer) = if (queued.fileId == null) {
                    UploadPreparer(keys, index, padSizes = true, clock).prepare(queued, request, source).let { it.upload to it.keys }
                } else {
                    val fileId = checkNotNull(queued.fileId)
                    queued to checkNotNull(keys.transferKeys(fileId, checkNotNull(index.file(fileId)).sealedMeta))
                }
                try {
                    log("uploading a file of ${copy.length()} bytes")
                    UploadRunner(keys.sodium, storage(), index, File(dir, TEMP)).run(upload, transfer, source)
                } finally {
                    transfer.close()
                }
                copy.delete()
                val wanted = states.remove(uploadId)?.wanted ?: Wanted(request, queued.replaces)
                log("uploaded")
                afterUpload(checkNotNull(upload.fileId), upload, wanted)
                return
            } catch (e: CancellationException) {
                // Taken back to be written again: what went up is deleted, and it waits in the queue as it was.
                withContext(NonCancellable) { requeue(uploadId, wantedOf(uploadId, request, queued.replaces)) }
                throw e
            } catch (_: SourceChangedException) {
                // The runner took it out of the queue; it goes back in, to go up as it is now.
                val wanted = wantedOf(uploadId, request, queued.replaces)
                put(uploadId, wanted.request, replaces = wanted.replaces)
            } catch (e: FloodWaitException) {
                log("Telegram asks to wait ${e.seconds} s before the next upload")
                delay(e.seconds * 1000L)
            } catch (e: StorageException) {
                log("an upload failed (${e.javaClass.simpleName}); trying again")
                delay(pause)
                pause = minOf(pause * 2, MAX_RETRY_MS)
            } catch (e: Exception) {
                // Anything else, a row gone under it for one: tried again from what the index says then,
                // rather than left to wait for the next start.
                log("an upload stopped (${e.javaClass.simpleName}); trying again")
                delay(pause)
                pause = minOf(pause * 2, MAX_RETRY_MS)
            }
            changed()
        }
    }

    private fun wantedOf(uploadId: String, request: LocalUpload, replaces: Id?) = states[uploadId]?.wanted ?: Wanted(request, replaces)

    private suspend fun requeue(uploadId: String, wanted: Wanted) {
        val upload = index.uploads().find { it.uploadId == uploadId } ?: return
        if (upload.fileId == null) return
        runCatching { UploadRunner(keys.sodium, storage(), index, File(dir, TEMP)).cancel(upload) }
            .onFailure { log("taking back an upload failed (${it.javaClass.simpleName})") }
        put(uploadId, wanted.request, replaces = wanted.replaces)
    }

    /** Held deletes of replaced files, packed now that no replacing upload is left (see [OpQueue.sealHeld]). */
    suspend fun sealHeld(queue: OpQueue) = queue.sealHeld(keys, clock = clock)

    private companion object {
        const val QUIET_MS = 2_000L
        const val FIRST_RETRY_MS = 5_000L
        const val MAX_RETRY_MS = 5 * 60_000L
        const val COPY_BUFFER = 1 shl 20
        const val TEMP = "parts"
    }
}

/** The type the vault stores for a name, from its extension; the phone asks the system the same way. */
internal fun mimeOf(name: String): String? = URLConnection.guessContentTypeFromName(name)

/** truncate(2): shorter cuts, longer pads with zeros, which FileChannel.truncate alone does not do. */
private fun FileChannel.truncateOrExtend(size: Long) {
    if (size < size()) truncate(size) else if (size > size()) write(ByteBuffer.wrap(ByteArray(1)), size - 1)
}
