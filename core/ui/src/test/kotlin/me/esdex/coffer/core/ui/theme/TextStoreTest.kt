package me.esdex.coffer.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TextStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun defaultsAreArcanums() {
        val prefs = TextStore(File(temp.root, "text.properties")).prefs.value
        assertEquals(TextPrefs(14, monospace = true, lineNumbers = true, wordWrap = false, highlight = true, showWhitespace = false, renderMarkdown = true), prefs)
    }

    @Test
    fun eachSettingIsKeptByItself() {
        val file = File(temp.root, "nested/text.properties")
        val store = TextStore(file)
        store.update { it.copy(fontSizeSp = 20) }
        store.update { it.copy(wordWrap = true) }
        val expected = TextPrefs(fontSizeSp = 20, wordWrap = true)
        assertEquals(expected, store.prefs.value)
        assertEquals(expected, TextStore(file).prefs.value)
        // One switch flipped at a time, each on its own key: none follows another.
        for (flip in listOf<(TextPrefs) -> TextPrefs>(
            { it.copy(monospace = false) }, { it.copy(lineNumbers = false) }, { it.copy(highlight = false) },
            { it.copy(showWhitespace = true) }, { it.copy(renderMarkdown = false) },
        )) {
            val one = File(temp.newFolder(), "text.properties")
            TextStore(one).update(flip)
            assertEquals(flip(TextPrefs()), TextStore(one).prefs.value)
        }
    }

    @Test
    fun aFontSizeOutsideTheRangeIsNotKept() {
        val file = File(temp.root, "text.properties")
        TextStore(file).update { it.copy(fontSizeSp = 99) }
        assertEquals(TextPrefs.FONT_SIZES.last, TextStore(file).prefs.value.fontSizeSp)
        file.writeText("fontSizeSp=3\nwordWrap=perhaps\nhighlight=false\n")
        assertEquals(TextPrefs(highlight = false), TextStore(file).prefs.value)
    }
}
