package me.esdex.coffer.desktop.drive

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.sync.VaultTree

/**
 * The vault as paths, what a file manager sees on the drive: the folders as the app shows them ([VaultTree]:
 * an unknown or looping parent puts a folder in the root, SPEC §5.2) and the files that are whole, as the
 * Android file picker lists them. A path must name one thing, where the vault may hold two with one name:
 * the second gets " (2)" before its extension, in the order of their ids, so names stay put as the vault
 * changes elsewhere. Files being written on the drive show as [Draft]s until their upload is whole; a file a
 * draft takes the place of no longer shows.
 */
class DriveTree private constructor(private val entries: Map<String, Entry>) {

    sealed interface Entry {
        /** Milliseconds since the epoch. */
        val modifiedAt: Long
    }

    /** [folderId] is null for the root. */
    class Folder(val folderId: Id?, val children: List<String>, override val modifiedAt: Long) : Entry

    class File(val fileId: Id, val size: Long, val createdAt: Long, override val modifiedAt: Long) : Entry

    /** A file being written here: its bytes in a copy on this computer until its upload is whole. */
    class Draft(val uploadId: String, val size: Long, override val modifiedAt: Long) : Entry

    /** Where a draft shows: its folder (the root when null or gone), its name, and what it holds so far. */
    class DraftInfo(val uploadId: String, val folderId: Id?, val name: String, val size: Long, val modifiedAt: Long, val replaces: Id?)

    /** What [path] names, "/" being the root; null if nothing. */
    operator fun get(path: String): Entry? = entries[path]

    companion object {
        val EMPTY = DriveTree(mapOf(ROOT to Folder(null, emptyList(), 0)))

        private const val ROOT = "/"

        /** The tree of [vault], showing of its files those in [whole], and the [drafts] being written. */
        fun of(vault: VaultTree, whole: Map<Id, IndexedFile>, drafts: List<DraftInfo> = emptyList()): DriveTree {
            val entries = HashMap<String, Entry>()
            val replaced = drafts.mapNotNullTo(HashSet()) { it.replaces }
            val draftsIn = drafts.groupBy { draft -> draft.folderId?.takeIf { it in vault.folders } }

            /** Lays out the folder [folderId] (null: the root) at [path]; its newest file's time is the folder's. */
            fun layOut(folderId: Id?, path: String): Long {
                val folders = vault.childFolders(folderId).map { Item(vault.folders.getValue(it).name, it, folder = true) }
                val files = vault.childFiles(folderId).filter { it in whole && it !in replaced }.map { Item(vault.files.getValue(it).name, it, folder = false) }
                val drafts = draftsIn[folderId].orEmpty().map { Item(it.name, null, folder = false, draft = it) }
                val names = uniqueNames(folders + files + drafts)
                var newest = 0L
                for ((item, name) in names) {
                    val childPath = if (path == ROOT) "$ROOT$name" else "$path/$name"
                    val draft = item.draft
                    val time = if (item.folder) {
                        layOut(item.id, childPath)
                    } else if (draft != null) {
                        entries[childPath] = Draft(draft.uploadId, draft.size, draft.modifiedAt)
                        draft.modifiedAt
                    } else {
                        val fileId = checkNotNull(item.id)
                        val meta = vault.files.getValue(fileId)
                        entries[childPath] = File(fileId, whole.getValue(fileId).plainSize, meta.createdAt, meta.modifiedAt)
                        meta.modifiedAt
                    }
                    newest = maxOf(newest, time)
                }
                entries[path] = Folder(folderId, names.map { it.second }, newest)
                return newest
            }
            layOut(null, ROOT)
            return DriveTree(entries)
        }

        /** A folder, a file (by [id]) or a [draft]. Drafts come after what the vault holds under the same name. */
        private class Item(val name: String, val id: Id?, val folder: Boolean, val draft: DraftInfo? = null) {
            val key: String = id?.toByteArray()?.joinToString("") { "%02x".format(it) } ?: "~${draft?.uploadId}"
        }

        /** Each item with the name it shows under: first come by id keeps its own, the others are numbered. */
        private fun uniqueNames(items: List<Item>): List<Pair<Item, String>> {
            val taken = HashSet<String>()
            val sorted = items.sortedWith(compareBy({ it.name }, { it.key }))
            // Names as they are first, so a numbered one never takes the name another has of its own.
            sorted.mapTo(taken) { pathName(it.name) }
            val given = HashSet<String>()
            return sorted.map { item ->
                val own = pathName(item.name)
                val name = if (given.add(own)) {
                    own
                } else {
                    generateSequence(2) { it + 1 }.map { numbered(own, it) }.first { it !in taken && it !in given }.also { given += it }
                }
                item to name
            }
        }

        /**
         * [name] as a path can hold it: a slash, which Coffer allows in a name and a path cannot, becomes "∕"
         * (U+2215), and "." and ".." are taken by the file system. Within Linux's 255 bytes, the extension kept.
         */
        internal fun pathName(name: String): String {
            val safe = name.replace('/', '\u2215').replace('\u0000', '_').let { if (it == "." || it == "..") "${it}_" else it }
            return fit(safe, "")
        }

        /** [name] with " ([n])" before its extension. */
        internal fun numbered(name: String, n: Int): String = fit(name, " ($n)")

        private fun fit(name: String, suffix: String): String {
            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0 && FileNames.utf8Size(name.substring(dot)) <= MAX_EXTENSION_BYTES) name.substring(dot) else ""
            val base = name.substring(0, name.length - extension.length)
            val room = MAX_NAME_BYTES - FileNames.utf8Size(suffix) - FileNames.utf8Size(extension)
            return truncate(base, room) + suffix + extension
        }

        /** The longest start of [text] within [maxBytes] of UTF-8, cut between code points. */
        private fun truncate(text: String, maxBytes: Int): String {
            var bytes = 0
            var end = 0
            while (end < text.length) {
                val next = text.offsetByCodePoints(end, 1)
                val size = FileNames.utf8Size(text.substring(end, next))
                if (bytes + size > maxBytes) break
                bytes += size
                end = next
            }
            return text.substring(0, end)
        }

        private const val MAX_NAME_BYTES = 255
        private const val MAX_EXTENSION_BYTES = 32
    }
}
