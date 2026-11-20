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

    fun begin(root: File, name: String, mime: String, original: Id?, folder: Id?, hash: Bytes, now: Long): EditSession {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, OpenFiles.safeName(name)).createNewFile()
        return EditSession(id, dir).apply {
            update {
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

    /** The editor closed the copy after writing to it. */
    fun written(root: File, id: String, now: Long) {
        get(root, id)?.update {
            it.setProperty(EditSession.DIRTY, "true")
            it.setProperty(EditSession.TOUCHED, now.toString())
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

    /** Deletes the sessions with nothing left to send that no editor wrote to for [IDLE_MILLIS]. */
    fun sweep(root: File, pending: Set<String>, now: Long) {
        for (session in all(root)) {
            val sending = session.upload?.let { it in pending } == true
            if (!session.dirty && !sending && now - session.touched > IDLE_MILLIS) File(root, session.id).deleteRecursively()
        }
    }

    private const val EDITED = " (edited)"
    private const val FORWARDS = "forwards.properties"
    private val ID = Regex("[0-9a-f-]{36}")
}
