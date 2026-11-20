package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileNames
import java.util.Locale

/** What to do with a file whose name is taken where it goes. */
enum class FileChoice { KEEP_BOTH, SKIP, REPLACE }

/** What to do with a folder whose name is taken where it goes. Replacing a whole folder is not offered. */
enum class FolderChoice { MERGE, KEEP_BOTH, SKIP }

/** Something going into a folder: a file, or a folder with what is in it. [T] is whatever it is. */
sealed interface Incoming<T> {
    val name: String
    val item: T

    class File<T>(override val name: String, override val item: T) : Incoming<T>
    class Folder<T>(override val name: String, override val item: T, val children: List<Incoming<T>>) : Incoming<T>
}

/**
 * Where an [Incoming] goes, once its name is settled: under [name], which may differ from the one it
 * came with. A file may take the place of [replaces]; a folder may go into [existing] instead of being
 * made, and then its [children] were checked against what that folder holds.
 */
sealed interface Placed<T> {
    val name: String
    val item: T

    // Not the name, nor what came in, in any text: logs carry no names (audit 3, DATA3-13).
    data class File<T>(override val name: String, override val item: T, val replaces: Id? = null) : Placed<T> {
        override fun toString(): String = "Placed.File(name=***, replaces=$replaces)"
    }

    data class Folder<T>(override val name: String, override val item: T, val existing: Id?, val children: List<Placed<T>>) : Placed<T> {
        override fun toString(): String = "Placed.Folder(name=***, existing=$existing, children=${children.size})"
    }
}

/** A name taken in [folderId] (null for the root), for the user to decide about. */
sealed interface Conflict {
    val name: String
    val folderId: Id?

    data class File(override val name: String, override val folderId: Id?) : Conflict {
        override fun toString(): String = "Conflict.File(name=***, folderId=$folderId)"
    }

    data class Folder(override val name: String, override val folderId: Id?) : Conflict {
        override fun toString(): String = "Conflict.Folder(name=***, folderId=$folderId)"
    }
}

/** The user's word on a conflict, and whether it goes for every other one of its kind too. */
data class Decision<C>(val choice: C, val forAll: Boolean = false)

/** Asks the user about a taken name, or answers for them from their setting. */
interface ConflictDecider {
    suspend fun file(conflict: Conflict.File): Decision<FileChoice>
    suspend fun folder(conflict: Conflict.Folder): Decision<FolderChoice>

    companion object {
        /** The same answer to every conflict, as the setting gives it. */
        fun always(file: FileChoice, folder: FolderChoice) = object : ConflictDecider {
            override suspend fun file(conflict: Conflict.File) = Decision(file, forAll = true)
            override suspend fun folder(conflict: Conflict.Folder) = Decision(folder, forAll = true)
        }
    }
}

/** The user left a question about a taken name unanswered: nothing of what was asked for happens. */
class ConflictsCancelled : Exception("the user cancelled")

/**
 * Settles names before anything goes into a folder that already holds the same ones (decision
 * 2026-11-19): for each taken name, [decideFile] or [decideFolder] says what to do, until a decision
 * for all of a kind makes the rest. Names are the same if they differ only in case, as on the phone.
 * Two incoming items of one name are both kept, the second numbered.
 */
class NameConflicts(
    private val tree: VaultTree,
    private val decider: ConflictDecider,
    /** Items that are moving: where they are now does not count as taken. */
    private val leaving: Set<Id> = emptySet(),
) {
    private var allFiles: FileChoice? = null
    private var allFolders: FolderChoice? = null

    /** [incoming] into [folderId], what is left of it after the user's decisions. */
    suspend fun <T> place(folderId: Id?, incoming: List<Incoming<T>>): List<Placed<T>> {
        val folders = tree.childFolders(folderId).filter { it !in leaving }.associateBy { key(tree.folders.getValue(it).name) }
        val files = tree.childFiles(folderId).filter { it !in leaving }.associateBy { key(tree.files.getValue(it).name) }
        val taken = HashSet<String>().apply { addAll(folders.keys); addAll(files.keys) }
        // Names given out in this go: a second item of one of them is numbered, never asked about.
        val given = HashSet<String>()
        val placed = mutableListOf<Placed<T>>()
        for (item in incoming) {
            val k = key(item.name)
            when (item) {
                is Incoming.Folder -> {
                    val existing = folders[k]?.takeIf { k !in given }
                    if (existing == null) {
                        val name = if (k in given || k in files) numbered(item.name, isFile = false, taken) else item.name
                        placed += Placed.Folder(name, item.item, null, fresh(item.children))
                        given += key(name)
                        taken += key(name)
                        continue
                    }
                    when (folderChoice(Conflict.Folder(item.name, folderId))) {
                        FolderChoice.MERGE -> placed += Placed.Folder(tree.folders.getValue(existing).name, item.item, existing, place(existing, item.children))
                        FolderChoice.KEEP_BOTH -> {
                            val name = numbered(item.name, isFile = false, taken)
                            placed += Placed.Folder(name, item.item, null, fresh(item.children))
                            taken += key(name)
                        }
                        FolderChoice.SKIP -> Unit
                    }
                    given += k
                }
                is Incoming.File -> {
                    val existing = files[k]?.takeIf { k !in given }
                    if (existing == null) {
                        val name = if (k in given || k in folders) numbered(item.name, isFile = true, taken) else item.name
                        placed += Placed.File(name, item.item)
                        given += key(name)
                        taken += key(name)
                        continue
                    }
                    when (fileChoice(Conflict.File(item.name, folderId))) {
                        FileChoice.REPLACE -> placed += Placed.File(item.name, item.item, replaces = existing)
                        FileChoice.KEEP_BOTH -> {
                            val name = numbered(item.name, isFile = true, taken)
                            placed += Placed.File(name, item.item)
                            taken += key(name)
                        }
                        FileChoice.SKIP -> Unit
                    }
                    given += k
                }
            }
        }
        return placed
    }

    /** Into a folder made just now: nothing is taken but what comes in with it. */
    private fun <T> fresh(incoming: List<Incoming<T>>): List<Placed<T>> {
        val taken = HashSet<String>()
        return incoming.map { item ->
            val name = if (key(item.name) in taken) numbered(item.name, item is Incoming.File, taken) else item.name
            taken += key(name)
            when (item) {
                is Incoming.File -> Placed.File(name, item.item)
                is Incoming.Folder -> Placed.Folder(name, item.item, null, fresh(item.children))
            }
        }
    }

    private suspend fun fileChoice(conflict: Conflict.File): FileChoice = allFiles ?: decider.file(conflict).also {
        if (it.forAll) allFiles = it.choice
    }.choice

    private suspend fun folderChoice(conflict: Conflict.Folder): FolderChoice = allFolders ?: decider.folder(conflict).also {
        if (it.forAll) allFolders = it.choice
    }.choice

    companion object {
        /** What the vault holds at [folderId] and below, as it would come into another folder. */
        fun incoming(tree: VaultTree, folderId: Id): Incoming.Folder<Id> = Incoming.Folder(
            tree.folders.getValue(folderId).name,
            folderId,
            tree.childFolders(folderId).map { incoming(tree, it) } + tree.childFiles(folderId).map { Incoming.File(tree.files.getValue(it).name, it) },
        )

        /** Names that count as the same: equal but for case. */
        fun key(name: String): String = name.lowercase(Locale.ROOT)

        /**
         * [name] with the lowest number not [taken] (by [key]): "file (1).pdf", "Photos (2)". A name that
         * already ends in a number goes on from its stem, so "file (1).pdf" gives "file (2).pdf". The stem
         * is shortened to keep the name within 255 bytes.
         */
        fun numbered(name: String, isFile: Boolean, taken: Set<String>): String {
            val dot = if (isFile) name.lastIndexOf('.') else -1
            val extension = if (dot > 0) name.substring(dot) else ""
            // An "extension" too long to leave the name any room is part of the name: kept whole, the number
            // came on top and the name went past 255 bytes, which stopped an import halfway (audit 3, Q3-10).
            if (FileNames.utf8Size(LONGEST_SUFFIX + extension) >= CofferFormat.MAX_NAME_BYTES) return numbered(name, isFile = false, taken)
            val whole = name.substring(0, name.length - extension.length)
            val match = NUMBERED.matchEntire(whole)
            val stem = match?.groupValues?.get(1) ?: whole
            var n = match?.groupValues?.get(2)?.toIntOrNull()?.plus(1) ?: 1
            while (true) {
                val suffix = " ($n)"
                val room = CofferFormat.MAX_NAME_BYTES - FileNames.utf8Size(suffix + extension)
                val candidate = shortened(stem, room) + suffix + extension
                if (key(candidate) !in taken) return candidate
                n++
            }
        }

        private val NUMBERED = Regex("(.+) \\((\\d{1,9})\\)")
        private const val LONGEST_SUFFIX = " (999999999)"

        private fun shortened(text: String, maxBytes: Int): String {
            var bytes = 0
            var end = 0
            while (end < text.length) {
                val codePoint = text.codePointAt(end)
                val size = FileNames.utf8Size(String(Character.toChars(codePoint)))
                if (bytes + size > maxBytes) break
                bytes += size
                end += Character.charCount(codePoint)
            }
            return text.substring(0, end)
        }
    }
}
