package me.esdex.coffer.core.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * How the text viewer shows a file, as in Arcanum's editor settings: each switch by itself, since a
 * config file and a note want different things.
 */
data class TextPrefs(
    val fontSizeSp: Int = DEFAULT_FONT_SIZE,
    val monospace: Boolean = true,
    val lineNumbers: Boolean = true,
    // Off by default: a line of a config file or of code is one line, and wrapping hides that. What
    // does not fit is reached by dragging sideways.
    val wordWrap: Boolean = false,
    val highlight: Boolean = true,
    val showWhitespace: Boolean = false,
    // On by default: a note is opened to be read, its marks are for writing.
    val renderMarkdown: Boolean = true,
) {
    companion object {
        const val DEFAULT_FONT_SIZE = 14
        val FONT_SIZES = 10..24
    }
}

/** Where [TextPrefs] is kept: a file of its own beside the player's, kept over a sign-out. */
class TextStore(private val file: File) {
    private val state = MutableStateFlow(read())
    val prefs: StateFlow<TextPrefs> = state.asStateFlow()

    @Synchronized
    fun update(change: (TextPrefs) -> TextPrefs) {
        val next = change(state.value).let { it.copy(fontSizeSp = it.fontSizeSp.coerceIn(TextPrefs.FONT_SIZES)) }
        val props = Properties()
        props.setProperty(FONT_SIZE, next.fontSizeSp.toString())
        for ((key, get) in FLAGS) props.setProperty(key, get(next).toString())
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { props.store(it, null) }
        check(temp.renameTo(file)) { "could not store ${file.name}" }
        state.value = next
    }

    private fun read(): TextPrefs {
        if (!file.exists()) return TextPrefs()
        val props = Properties().apply { file.inputStream().use(::load) }
        fun flag(key: String, default: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: default
        val d = TextPrefs()
        return TextPrefs(
            fontSizeSp = props.getProperty(FONT_SIZE)?.toIntOrNull()?.takeIf { it in TextPrefs.FONT_SIZES } ?: d.fontSizeSp,
            monospace = flag(MONOSPACE, d.monospace),
            lineNumbers = flag(LINE_NUMBERS, d.lineNumbers),
            wordWrap = flag(WORD_WRAP, d.wordWrap),
            highlight = flag(HIGHLIGHT, d.highlight),
            showWhitespace = flag(WHITESPACE, d.showWhitespace),
            renderMarkdown = flag(RENDER_MARKDOWN, d.renderMarkdown),
        )
    }

    private companion object {
        const val FONT_SIZE = "fontSizeSp"
        const val MONOSPACE = "monospace"
        const val LINE_NUMBERS = "lineNumbers"
        const val WORD_WRAP = "wordWrap"
        const val HIGHLIGHT = "highlight"
        const val WHITESPACE = "showWhitespace"
        const val RENDER_MARKDOWN = "renderMarkdown"
        val FLAGS: List<Pair<String, (TextPrefs) -> Boolean>> = listOf(
            MONOSPACE to { it.monospace },
            LINE_NUMBERS to { it.lineNumbers },
            WORD_WRAP to { it.wordWrap },
            HIGHLIGHT to { it.highlight },
            WHITESPACE to { it.showWhitespace },
            RENDER_MARKDOWN to { it.renderMarkdown },
        )
    }
}
