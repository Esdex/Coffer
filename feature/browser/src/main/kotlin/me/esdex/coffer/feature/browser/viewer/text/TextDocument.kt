package me.esdex.coffer.feature.browser.viewer.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A text file as the viewer shows it, after Arcanum's TextDocument (pre-release item 5): the
 * characters, line breaks made "\n", and whether the bytes were UTF-8 at all.
 */
class TextDocument(val text: String, val decodedAsFallback: Boolean) {

    companion object {
        /**
         * Anything longer is not read in. The platform text view lays the whole file out on the main
         * thread; measured on a Nothing Phone (1), 256 KB takes 0.45-0.7 s to open and 0.2-0.3 s to
         * re-lay after a setting changes, while 1 MB took 2.5-3.4 s and ended in an ANR.
         */
        const val MAX_BYTES = 256L * 1024

        private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

        /**
         * Reads [bytes] as text, or null when they are not text: a NUL in the first 8 KB, which no
         * encoding read here produces and every binary format has within a few hundred bytes.
         */
        fun decode(bytes: ByteArray): TextDocument? {
            val head = bytes.copyOf(minOf(bytes.size, 8 * 1024))
            if (head.any { it == 0.toByte() } && !startsWith(bytes, BOM_UTF16LE) && !startsWith(bytes, BOM_UTF16BE)) return null

            val (bom, charset) = when {
                startsWith(bytes, BOM_UTF8) -> BOM_UTF8 to StandardCharsets.UTF_8
                startsWith(bytes, BOM_UTF16LE) -> BOM_UTF16LE to StandardCharsets.UTF_16LE
                startsWith(bytes, BOM_UTF16BE) -> BOM_UTF16BE to StandardCharsets.UTF_16BE
                else -> ByteArray(0) to StandardCharsets.UTF_8
            }
            val body = bytes.copyOfRange(bom.size, bytes.size)
            // Not valid UTF-8: Latin-1 maps every byte to a character, so an old file in some other
            // encoding shows wrong rather than not at all, and the viewer says so.
            val strict = strictDecode(body, charset)
            val raw = strict ?: String(body, StandardCharsets.ISO_8859_1)
            body.fill(0)
            return TextDocument(raw.replace("\r\n", "\n").replace('\r', '\n'), decodedAsFallback = strict == null)
        }

        private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

        private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

/**
 * Which files open in the text viewer: names people expect to be text, as in Arcanum, and whatever
 * the sending app called text. Anything else still opens in another app.
 */
object TextFiles {
    private val EXTENSIONS = setOf(
        "txt", "text", "md", "markdown", "log", "csv", "tsv",
        "json", "xml", "html", "htm", "svg", "xhtml", "plist",
        "ini", "conf", "cfg", "properties", "toml", "yml", "yaml", "env", "gitignore",
        "sh", "bash", "zsh", "fish", "profile", "bashrc",
        "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc",
        "js", "ts", "py", "rs", "go", "swift", "php", "rb",
        "css", "scss", "sql", "gradle", "srt", "vtt", "diff", "patch",
    )

    fun isText(name: String, mime: String): Boolean = mime.startsWith("text/") || name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    fun isMarkdown(name: String): Boolean = SyntaxHighlighter.of(name) == Syntax.MARKDOWN
}
