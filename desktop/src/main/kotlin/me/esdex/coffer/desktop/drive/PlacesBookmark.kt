package me.esdex.coffer.desktop.drive

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The drive in the Places of Dolphin and KDE's file dialogs, as Cryptomator puts its vaults there (Esdex,
 * 2026-11-22): a bookmark in [file], KDE's own list of places, marked as Coffer's so only that one is ever
 * touched. Added when the drive mounts, taken out when it goes; one left by a crash goes at the next start.
 *
 * Edited as text, the rest left byte for byte; written whole and swapped in, as Dolphin writes it. A missing
 * file is left missing: KDE makes its default places then, which a file with only Coffer's would lose.
 */
class PlacesBookmark(private val file: Path) {

    /** Shows the drive at [at] as [title]; the one shown before, if any, gives way. */
    @Synchronized
    fun add(at: Path, title: String) {
        val text = read() ?: return
        val without = remove(text)
        val end = without.lastIndexOf(XBEL_END)
        if (end < 0) return
        val href = at.toUri().toString().let { if (it.endsWith("/")) it else "$it/" }
        val block = """
            |    <bookmark href="${escape(href)}">
            |        <title>${escape(title)}</title>
            |        <info>
            |            <metadata owner="http://freedesktop.org">
            |                <bookmark:icon name="$ICON"/>
            |            </metadata>
            |            <metadata owner="$OWNER">
            |                <id>$ID</id>
            |            </metadata>
            |        </info>
            |    </bookmark>
            |""".trimMargin()
        write(without.substring(0, end) + block + without.substring(end))
    }

    /** Takes the drive out of Places, if it is there. */
    @Synchronized
    fun remove() {
        val text = read() ?: return
        val without = remove(text)
        if (without != text) write(without)
    }

    private fun read(): String? = if (Files.isRegularFile(file)) Files.readString(file) else null

    private fun write(text: String) {
        val temp = Files.createTempFile(file.parent, ".user-places", ".xbel")
        try {
            Files.writeString(temp, text)
            runCatching { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file)) }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        /** Marks Coffer's bookmark among the others, as Cryptomator marks its own. */
        const val OWNER = "https://github.com/Esdex/Coffer"
        private const val ID = "coffer-drive"
        private const val ICON = "drive-harddisk-encrypted"
        private const val XBEL_END = "</xbel>"

        // A bookmark whose own text holds Coffer's mark: never reaching past its own </bookmark>, so no other is taken.
        private val OURS = Regex(
            """[ \t]*<bookmark href="[^"]*">(?:(?!</bookmark>).)*?<metadata owner="${Regex.escape(OWNER)}">(?:(?!</bookmark>).)*</bookmark>[ \t]*\r?\n?""",
            RegexOption.DOT_MATCHES_ALL,
        )

        internal fun remove(text: String): String = OURS.replace(text, "")

        private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** KDE's list of places: $XDG_DATA_HOME/user-places.xbel. */
        fun kde(): PlacesBookmark {
            val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".local", "share")
            return PlacesBookmark(base.resolve("user-places.xbel"))
        }
    }
}
