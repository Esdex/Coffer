package me.esdex.coffer.feature.browser.stats

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.feature.browser.viewer.text.TextFiles

/** The kinds the Storage screen sorts files into (pre-release item 6); anything not one of the first four is Other. */
enum class FileKind {
    PICTURES, VIDEOS, MUSIC, DOCUMENTS, OTHER;

    companion object {
        private val DOCUMENT_TYPES = setOf(
            "application/pdf", "application/msword", "application/rtf", "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.presentation",
            "application/vnd.ms-excel", "application/vnd.ms-powerpoint", "application/epub+zip",
        )
        private val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub", "djvu", "fb2")

        /** By the type first, as the viewers decide, then by the name for a type the sending app left vague. */
        fun of(name: String, mime: String): FileKind {
            val extension = name.substringAfterLast('.', "").lowercase()
            return when {
                // SVG is an image by type, but Coffer shows it as text, so it counts with the documents.
                mime == "image/svg+xml" -> DOCUMENTS
                mime.startsWith("image/") -> PICTURES
                mime.startsWith("video/") -> VIDEOS
                mime.startsWith("audio/") -> MUSIC
                mime in DOCUMENT_TYPES || mime.startsWith("application/vnd.openxmlformats-officedocument.") -> DOCUMENTS
                extension in DOCUMENT_EXTENSIONS || TextFiles.isText(name, mime) -> DOCUMENTS
                else -> OTHER
            }
        }
    }
}

/** One file as the Storage screen sees it. */
data class StatsFile(val fileId: Id, val name: String, val mime: String, val size: Long, val folderId: Id?) {
    val kind: FileKind get() = FileKind.of(name, mime)
}

/** How many files of one kind, and how much they hold. */
data class KindTotal(val kind: FileKind, val count: Int, val bytes: Long)

/** The vault by kind: every kind present in [FileKind] order, kinds with no file included, so the legend does not jump. */
data class Breakdown(val kinds: List<KindTotal>, val folders: Int) {
    val files: Int get() = kinds.sumOf { it.count }
    val bytes: Long get() = kinds.sumOf { it.bytes }

    companion object {
        fun of(files: Collection<StatsFile>, folders: Int): Breakdown {
            val byKind = files.groupBy { it.kind }
            return Breakdown(FileKind.entries.map { kind -> byKind[kind].orEmpty().let { KindTotal(kind, it.size, it.sumOf(StatsFile::size)) } }, folders)
        }
    }
}
