package me.esdex.coffer.feature.browser.transfer

import me.esdex.coffer.core.crypto.Id
import java.io.File
import java.util.Properties

/**
 * Coffer's folders and files as documents of the system file picker (decision 2026-11-20, see
 * [VaultDocumentsProvider]): their ids, and what an id another app holds stands for now. A save there
 * goes in through an edit session (see [EditSessions]) and takes the place of the file with a new one,
 * under a new id; until it is in the vault, the session's copy is the document.
 */
object PickerDocuments {
    const val ROOT = "root"

    sealed interface Doc {
        data object Root : Doc
        data class Folder(val id: Id) : Doc
        data class File(val id: Id) : Doc

        /** A file made in the picker: its edit session, until the file is in the vault and after. */
        data class Made(val session: String) : Doc
    }

    fun id(doc: Doc): String = when (doc) {
        Doc.Root -> ROOT
        is Doc.Folder -> FOLDER + EditSession.hex(doc.id.toByteArray())
        is Doc.File -> FILE + EditSession.hex(doc.id.toByteArray())
        is Doc.Made -> MADE + doc.session
    }

    /** The document [id] names, if it is one; the id comes from another app, so it is checked. */
    fun parse(id: String): Doc? = when {
        id == ROOT -> Doc.Root
        id.startsWith(FOLDER) -> idOf(id.removePrefix(FOLDER))?.let(Doc::Folder)
        id.startsWith(FILE) -> idOf(id.removePrefix(FILE))?.let(Doc::File)
        id.startsWith(MADE) -> id.removePrefix(MADE).takeIf(SESSION::matches)?.let(Doc::Made)
        else -> null
    }

    /** What a file document holds now: a copy saved and not yet in the vault, or a file in the vault. */
    sealed interface Content {
        data class Copy(val session: EditSession) : Content
        data class Stored(val fileId: Id) : Content
    }

    /**
     * What [doc] holds now. [chain] is where it went (see [DocumentForwards.chain]), [ready] tells a file
     * in the vault, whole, from one that is not, and [pending] are the uploads still queued. A copy saved
     * since its last upload, or with its upload still queued, is newer than the vault; so is a file made
     * here and not saved yet, empty.
     */
    fun content(doc: Doc, chain: List<Id>, sessions: List<EditSession>, ready: (Id) -> Boolean, pending: Set<String>): Content? {
        val matching = sessionsOf(doc, chain, sessions)
        val newer = matching.filter { it.dirty || it.upload in pending || (doc is Doc.Made && it.upload == null) }.maxByOrNull { it.touched }
        if (newer != null) return Content.Copy(newer)
        return chain.lastOrNull(ready)?.let(Content::Stored)
    }

    /**
     * The session a write to [doc] goes into: one holding what was saved last, or for a file made here,
     * its own. Null when a new one is to begin from the file in the vault.
     */
    fun writable(doc: Doc, chain: List<Id>, sessions: List<EditSession>): EditSession? =
        sessionsOf(doc, chain, sessions).filter { doc is Doc.Made || it.dirty || it.upload != null }.maxByOrNull { it.touched }

    private fun sessionsOf(doc: Doc, chain: List<Id>, sessions: List<EditSession>): List<EditSession> = when (doc) {
        is Doc.Made -> sessions.filter { it.id == doc.session }
        is Doc.File -> sessions.filter { it.original in chain || it.uploadFile in chain }
        else -> emptyList()
    }

    /** Where [doc] starts in [DocumentForwards]: a file's id, or a made file's session. */
    fun forwardKey(doc: Doc): String? = when (doc) {
        is Doc.File -> EditSession.hex(doc.id.toByteArray())
        is Doc.Made -> doc.session
        else -> null
    }

    private fun idOf(hex: String): Id? = hex.takeIf(HEX_ID::matches)?.let { EditSession.idOf(it) }

    private const val FOLDER = "d:"
    private const val FILE = "f:"
    private const val MADE = "n:"
    private val HEX_ID = Regex("[0-9a-f]{32}")
    private val SESSION = Regex("[0-9a-f-]{36}")
}

/**
 * Where a document went. Each save takes the place of a file with a new one (SPEC §5.3), and an app that
 * keeps a document, as KeePassDX keeps its database, would lose it at the first save; so each save leaves
 * a forward from the file it replaced, and from its session, to the file it made. Only random ids are
 * kept, no names. A forward is written once the upload is prepared, before it is whole: following them,
 * a document stops at the last file that is (see [PickerDocuments.content]).
 *
 * Kept to [LIMIT], the forwards used least lately going first; [settle] points a chain followed at the
 * file it ends at, so the documents in use stay one step from their file however often they are saved.
 */
class DocumentForwards private constructor(private val links: Map<String, Link>) {
    private class Link(val to: String, val at: Long)

    /** [from] itself if it is a file's id, then each file it was forwarded to, in order. */
    fun chain(from: String): List<Id> {
        val keys = mutableListOf(from)
        while (keys.size <= links.size) {
            val next = links[keys.last()]?.to ?: break
            if (next in keys) break
            keys += next
        }
        return keys.mapNotNull { key -> key.takeIf(HEX_ID::matches)?.let { EditSession.idOf(it) } }
    }

    companion object {
        const val LIMIT = 1000

        private val lock = Any()
        private val HEX_ID = Regex("[0-9a-f]{32}")

        fun read(file: File): DocumentForwards = synchronized(lock) { DocumentForwards(load(file)) }

        fun add(file: File, from: String, to: Id, now: Long) = change(file) { it[from] = Link(EditSession.hex(to.toByteArray()), now) }

        /** Points each of [keys], the start of a chain and the steps it took, straight at [to], where it ends. */
        fun settle(file: File, keys: List<String>, to: Id, now: Long) = change(file) { links ->
            val target = EditSession.hex(to.toByteArray())
            for (key in keys) if (key != target) links[key] = Link(target, now)
        }

        private fun change(file: File, edit: (MutableMap<String, Link>) -> Unit) = synchronized(lock) {
            val links = load(file).toMutableMap()
            edit(links)
            val kept = links.entries.sortedByDescending { it.value.at }.take(LIMIT)
            val props = Properties()
            for ((key, link) in kept) props.setProperty(key, "${link.to} ${link.at}")
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { props.store(it, null) }
            check(temp.renameTo(file)) { "could not store the forwards" }
        }

        private fun load(file: File): Map<String, Link> {
            if (!file.isFile) return emptyMap()
            val props = Properties().apply { file.inputStream().use(::load) }
            return props.stringPropertyNames().mapNotNull { key ->
                val (to, at) = props.getProperty(key).split(' ').takeIf { it.size == 2 } ?: return@mapNotNull null
                key to Link(to, at.toLongOrNull() ?: 0L)
            }.toMap()
        }
    }
}
