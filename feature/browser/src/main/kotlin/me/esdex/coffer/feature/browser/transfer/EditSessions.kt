package me.esdex.coffer.feature.browser.transfer

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.sync.NameConflicts
import me.esdex.coffer.core.sync.VaultTree
import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * A vault file decrypted for editing in another app (decision 2026-11-19): its copy, handed out with
 * write access by [EditProvider], and what Coffer knows about it, in `session.properties` beside it.
 * Unlike a copy to open, it stays when the vault locks: the editor may save after that, and the change
 * waits for the next unlock to be encrypted and sent. Nothing secret is in the properties, only ids,
 * the type and the hash of what was last saved; the name is the copy's own.
 */
class EditSession internal constructor(val id: String, private val dir: File) {
    private val props = Properties().apply { File(dir, PROPS).takeIf { it.exists() }?.inputStream()?.use(::load) }

    /** The decrypted copy, under the file's name. */
    val copy: File get() = dir.listFiles()?.firstOrNull { it.name != PROPS && it.name != PROPS_TEMP } ?: File(dir, "file")

    /** The vault file a save takes the place of; none for a file made in the system file picker, not saved yet. */
    val original: Id? get() = props.getProperty(ORIGINAL)?.takeIf { it.isNotEmpty() }?.let(::idOf)

    /** Where the file was when the edit began, for a save that finds it gone. */
    val folder: Id? get() = props.getProperty(FOLDER)?.takeIf { it.isNotEmpty() }?.let(::idOf)

    val mime: String get() = props.getProperty(MIME) ?: "application/octet-stream"

    /** The content hash of the vault's version: a save that changed nothing is not sent. */
    val savedHash: Bytes get() = Bytes.of(bytesOf(props.getProperty(HASH)))

    /** The upload of the last save, and the file it makes, once it is prepared. */
    val upload: String? get() = props.getProperty(UPLOAD)?.takeIf { it.isNotEmpty() }
    val uploadFile: Id? get() = props.getProperty(UPLOAD_FILE)?.takeIf { it.isNotEmpty() }?.let(::idOf)

    /** Written by the editor since the last save went to the queue. */
    val dirty: Boolean get() = props.getProperty(DIRTY) == "true"

    /** When it was last written, or begun: an idle session goes after a while (see [EditSessions.sweep]). */
    val touched: Long get() = props.getProperty(TOUCHED)?.toLongOrNull() ?: 0L

    /** The copy holds the vault's version or what an editor wrote; false while a download fills it. */
    val ready: Boolean get() = props.getProperty(READY) != "false"

    /** The copy's time of change when Coffer last knew what it held: a later one is a save it did not hear. */
    val modified: Long get() = props.getProperty(MODIFIED)?.toLongOrNull() ?: 0L

    /** The process of Coffer's that handed the copy out for writing and has not heard it closed. */
    val writer: Int? get() = props.getProperty(WRITER)?.toIntOrNull()

    /** Its editor failed while writing: what the copy holds is no save, and it is never sent or handed out. */
    val broken: Boolean get() = props.getProperty(BROKEN) == "true"

    internal fun update(change: (Properties) -> Unit) {
        change(props)
        val temp = File(dir, PROPS_TEMP)
        temp.outputStream().use { props.store(it, null) }
        check(temp.renameTo(File(dir, PROPS))) { "could not store an edit session" }
    }

    override fun toString(): String = "EditSession(id=$id, dirty=$dirty)"

    internal companion object {
        const val PROPS = "session.properties"
        const val PROPS_TEMP = "session.properties.tmp"
        const val ORIGINAL = "original"
        const val FOLDER = "folder"
        const val MIME = "mime"
        const val HASH = "hash"
        const val UPLOAD = "upload"
        const val UPLOAD_FILE = "uploadFile"
        const val DIRTY = "dirty"
        const val TOUCHED = "touched"
        const val READY = "ready"
        const val MODIFIED = "modified"
        const val WRITER = "writer"
        const val BROKEN = "broken"

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun bytesOf(hex: String?): ByteArray = (hex ?: "").let { h -> ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        fun idOf(hex: String?): Id = Id.of(bytesOf(hex))
    }
}

/** Where a saved edit goes: in place of [replaces], or as a new file; [cancel] is a save before it still queued. */
data class EditSave(val name: String, val folderId: Id?, val replaces: Id?, val cancel: String?, val original: Id?)

object EditSessions {
    /** How long a session stays once nothing is left to send and the editor stopped writing. */
    const val IDLE_MILLIS = 60 * 60 * 1000L

    fun dir(context: Context) = File(context.noBackupFilesDir, "edit")

    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** An editor wrote a copy: time to look for saves. */
    val changes: SharedFlow<Unit> = changesFlow.asSharedFlow()

    /** A new session; not [ready] when a download is to fill its copy (see [filled]). */
    fun begin(root: File, name: String, mime: String, original: Id?, folder: Id?, hash: Bytes, now: Long, ready: Boolean = true): EditSession {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val copy = File(dir, OpenFiles.safeName(name)).apply { createNewFile() }
        return EditSession(id, dir).apply {
            update {
                it.setProperty(EditSession.READY, ready.toString())
                it.setProperty(EditSession.MODIFIED, copy.lastModified().toString())
                it.setProperty(EditSession.ORIGINAL, original?.let { o -> EditSession.hex(o.toByteArray()) }.orEmpty())
                it.setProperty(EditSession.FOLDER, folder?.let { f -> EditSession.hex(f.toByteArray()) }.orEmpty())
                it.setProperty(EditSession.MIME, mime)
                it.setProperty(EditSession.HASH, EditSession.hex(hash.toByteArray()))
                it.setProperty(EditSession.TOUCHED, now.toString())
            }
        }
    }

    /** The session [id], if there is one; [id] comes from a URI another app holds, so it is checked. */
    fun get(root: File, id: String): EditSession? {
        if (!ID.matches(id)) return null
        val dir = File(root, id)
        return if (File(dir, EditSession.PROPS).isFile) EditSession(id, dir) else null
    }

    fun all(root: File): List<EditSession> = root.listFiles()?.mapNotNull { get(root, it.name) }.orEmpty()

    /** The copy holds the vault's version now, downloaded or decrypted into it. */
    fun filled(root: File, id: String, now: Long) {
        get(root, id)?.let { session ->
            session.update {
                it.setProperty(EditSession.READY, "true")
                it.setProperty(EditSession.MODIFIED, session.copy.lastModified().toString())
                it.setProperty(EditSession.TOUCHED, now.toString())
            }
        }
    }

    /** Handed out for writing by this process [pid]: kept while it is open, however long (see [sweep]). */
    fun opened(root: File, id: String, pid: Int, now: Long) {
        get(root, id)?.update {
            it.setProperty(EditSession.WRITER, pid.toString())
            it.setProperty(EditSession.TOUCHED, now.toString())
        }
    }

    /**
     * The editor's side closed with an error, as when it crashed mid-write: a copy cut short, or emptied
     * by a truncating open, is no save. Sent, it took the place of the file and the file went for good
     * (audit 3, Q3-2). The vault's version stays; the copy goes at the next sweep.
     */
    fun failed(root: File, id: String, now: Long) {
        get(root, id)?.let { session ->
            session.update {
                it.setProperty(EditSession.BROKEN, "true")
                it.setProperty(EditSession.DIRTY, "false")
                it.setProperty(EditSession.TOUCHED, now.toString())
                it.setProperty(EditSession.MODIFIED, session.copy.lastModified().toString())
                it.remove(EditSession.WRITER)
            }
        }
    }

    /** The editor closed the copy after writing to it. */
    fun written(root: File, id: String, now: Long) {
        get(root, id)?.let { session ->
            session.update {
                it.setProperty(EditSession.DIRTY, "true")
                it.setProperty(EditSession.TOUCHED, now.toString())
                it.setProperty(EditSession.MODIFIED, session.copy.lastModified().toString())
                it.remove(EditSession.WRITER)
            }
        }
        changesFlow.tryEmit(Unit)
    }

    /**
     * The upload of a save was prepared: [fileId] is the file it makes. A document another app holds for the
     * file it replaces, or for the session itself, now leads to it (see [DocumentForwards]).
     */
    fun prepared(root: File, uploadId: String, fileId: Id, now: Long) {
        for (session in all(root)) {
            if (session.upload != uploadId) continue
            session.update { it.setProperty(EditSession.UPLOAD_FILE, EditSession.hex(fileId.toByteArray())) }
            val forwards = forwardsFile(root)
            DocumentForwards.add(forwards, session.id, fileId, now)
            session.original?.let { DocumentForwards.add(forwards, EditSession.hex(it.toByteArray()), fileId, now) }
        }
    }

    /** Where documents of the system file picker went, beside the sessions (see [DocumentForwards]). */
    fun forwardsFile(root: File) = File(root, FORWARDS)

    /** A save went to the queue as [uploadId], with content [hash]. */
    fun queued(session: EditSession, uploadId: String, hash: Bytes, original: Id?) = session.update {
        it.setProperty(EditSession.UPLOAD, uploadId)
        it.setProperty(EditSession.UPLOAD_FILE, "")
        it.setProperty(EditSession.HASH, EditSession.hex(hash.toByteArray()))
        it.setProperty(EditSession.DIRTY, "false")
        if (original != null) it.setProperty(EditSession.ORIGINAL, EditSession.hex(original.toByteArray()))
    }

    /** Written, but the same as what the vault has: nothing to send. */
    fun unchanged(session: EditSession) = session.update { it.setProperty(EditSession.DIRTY, "false") }

    /**
     * Where a save of [session] goes. A save before it still in the queue ([pending]) is cancelled, and
     * this one takes the place of the same file; one that went through ([ready] for its file) made the
     * file this one replaces. The file is followed where it was renamed or moved; gone, the save comes in
     * beside where it was, as "name (edited).ext", the folder gone too, at the top level. A file made in the
     * system file picker comes in under its own name until it is in the vault.
     */
    fun plan(session: EditSession, tree: VaultTree, pending: Set<String>, ready: (Id) -> Boolean): EditSave {
        val upload = session.upload
        val cancel = upload?.takeIf { it in pending }
        val previous = session.uploadFile?.takeIf { upload != null && cancel == null && ready(it) }
        val original = previous ?: session.original
        if (original != null && original in tree.files) {
            return EditSave(tree.files.getValue(original).name, tree.folderOf(original), original, cancel, original)
        }
        val folder = session.folder?.takeIf { it in tree.folders }
        val taken = (tree.childFiles(folder).map { tree.files.getValue(it).name } + tree.childFolders(folder).map { tree.folders.getValue(it).name })
            .mapTo(HashSet(), NameConflicts::key)
        val name = if (original == null) session.copy.name.let { if (NameConflicts.key(it) in taken) NameConflicts.numbered(it, isFile = true, taken) else it }
        else editedName(session.copy.name, taken)
        return EditSave(name, folder, null, cancel, null)
    }

    /** "report.docx" as "report (edited).docx", numbered after that if [taken]. */
    fun editedName(name: String, taken: Set<String>): String {
        val dot = name.lastIndexOf('.')
        val edited = if (dot > 0) name.substring(0, dot) + EDITED + name.substring(dot) else name + EDITED
        val fitted = vaultName(edited, FILE_FALLBACK_NAME)
        return if (NameConflicts.key(fitted) in taken) NameConflicts.numbered(fitted, isFile = true, taken) else fitted
    }

    /**
     * Deletes the sessions with nothing left to send that no editor wrote to for [IDLE_MILLIS]. Not one this
     * process [pid] handed out for writing and has not heard closed, for a day: an editor may keep a copy
     * open for long before it saves (audit 3, DATA3-2). A copy changed since Coffer last knew it, written
     * while the process that would have heard it was gone, is a save: marked written, not deleted. A copy
     * still being filled by a download is left to it for [FILLING_MILLIS].
     */
    fun sweep(root: File, pending: Set<String>, now: Long, pid: Int) {
        for (session in all(root)) {
            val dir = File(root, session.id)
            val sending = session.upload?.let { it in pending } == true
            if (session.broken) {
                // Not while an earlier save still reads it: its hash no longer matching, that upload fails alone.
                if (!sending) dir.deleteRecursively()
                continue
            }
            if (!session.ready) {
                if (now - session.touched > FILLING_MILLIS) dir.deleteRecursively()
                continue
            }
            val writer = session.writer
            if (writer == pid && now - session.touched <= WRITING_MILLIS) continue
            if (writer != null) session.update { it.remove(EditSession.WRITER) }
            if (!session.dirty && session.copy.lastModified() != session.modified) {
                written(root, session.id, now)
                continue
            }
            if (!session.dirty && !sending && now - session.touched > IDLE_MILLIS) dir.deleteRecursively()
        }
    }

    /** How long a copy handed out for writing is kept without a word from its editor. */
    const val WRITING_MILLIS = 24 * 60 * 60 * 1000L

    /** How long a download may take to fill a copy. */
    const val FILLING_MILLIS = 24 * 60 * 60 * 1000L

    private const val EDITED = " (edited)"
    private const val FORWARDS = "forwards.properties"
    private val ID = Regex("[0-9a-f-]{36}")
}
