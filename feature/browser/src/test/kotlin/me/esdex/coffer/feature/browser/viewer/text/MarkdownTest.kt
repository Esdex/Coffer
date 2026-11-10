package me.esdex.coffer.feature.browser.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dialect the text viewer renders: Arcanum's tests, less the checkbox that writes back. */
class MarkdownTest {

    @Test
    fun `headings carry their level and their text`() {
        val blocks = Markdown.parse("# One\n### Three\n")
        assertEquals(listOf(1 to "One", 3 to "Three"),
            blocks.filterIsInstance<MdBlock.Heading>().map { it.level to it.text })
    }

    @Test
    fun `a wrapped paragraph is one paragraph`() {
        val blocks = Markdown.parse("first line\nsecond line\n\nanother\n")
        val paragraphs = blocks.filterIsInstance<MdBlock.Paragraph>().map { it.text }
        assertEquals(listOf("first line second line", "another"), paragraphs)
    }

    @Test
    fun `a fenced block keeps its own lines and its language`() {
        val blocks = Markdown.parse("before\n\n```kotlin\nval a = 1\n\nval b = 2\n```\nafter\n")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\n\nval b = 2", code.text)
        // What follows the fence is a paragraph again, not more code.
        assertTrue(blocks.filterIsInstance<MdBlock.Paragraph>().any { it.text == "after" })
    }

    @Test
    fun `marks inside a fenced block are not read as marks`() {
        val blocks = Markdown.parse("```\n# not a heading\n- not a list\n```\n")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.Code)
    }

    @Test
    fun `lists keep their kind and their indent`() {
        val blocks = Markdown.parse("- one\n  - nested\n1. first\n2) second\n")
        val items = blocks.filterIsInstance<MdBlock.Item>()
        assertEquals(listOf("one" to null, "nested" to null, "first" to 1, "second" to 2),
            items.map { it.text to it.number })
        assertEquals(listOf(0, 1, 0, 0), items.map { it.indent })
    }

    @Test
    fun `a checkbox knows whether it is ticked`() {
        val tasks = Markdown.parse("- [ ] undone\n- [x] done\n  * [X] nested\n").filterIsInstance<MdBlock.Task>()
        assertEquals(listOf(false, true, true), tasks.map { it.checked })
        assertEquals(listOf("undone", "done", "nested"), tasks.map { it.text })
        assertEquals(listOf(0, 0, 1), tasks.map { it.indent })
    }

    @Test
    fun `a table is read with its alignments`() {
        val blocks = Markdown.parse(
            "| Name | Size | Note |\n" +
            "|:-----|-----:|:----:|\n" +
            "| one  | 1 KB | ok   |\n" +
            "| two  | 2 KB | also |\n" +
            "\nafter the table\n"
        )
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Name", "Size", "Note"), table.header)
        assertEquals(listOf(-1, 1, 0), table.aligns)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("two", "2 KB", "also"), table.rows[1])
        assertTrue(blocks.filterIsInstance<MdBlock.Paragraph>().any { it.text == "after the table" })
    }

    @Test
    fun `pipes without a divider under them stay text`() {
        val blocks = Markdown.parse("a | b | c\nd | e | f\n")
        assertTrue(blocks.none { it is MdBlock.Table })
        assertEquals("a | b | c d | e | f", blocks.filterIsInstance<MdBlock.Paragraph>().single().text)
    }

    @Test
    fun `front matter is read as properties, not as a rule and a paragraph`() {
        val blocks = Markdown.parse("---\ntitle: My note\ntags: one, two\n---\n\n# Heading\n")
        val front = blocks.filterIsInstance<MdBlock.FrontMatter>().single()
        assertEquals(listOf("title" to "My note", "tags" to "one, two"), front.entries)
        assertTrue(blocks.none { it is MdBlock.Rule })
        assertEquals("Heading", blocks.filterIsInstance<MdBlock.Heading>().single().text)
    }

    @Test
    fun `three dashes that are not front matter stay a rule`() {
        val blocks = Markdown.parse("text\n\n---\n\nmore text\n")
        assertTrue(blocks.any { it is MdBlock.Rule })
    }

    @Test
    fun `a wrapped list item is one item`() {
        val blocks = Markdown.parse("- a list item that goes on\n  and finishes here\n- the next one\n")
        val items = blocks.filterIsInstance<MdBlock.Item>()
        assertEquals(2, items.size)
        assertEquals("a list item that goes on and finishes here", items[0].text)
        assertTrue(blocks.none { it is MdBlock.Paragraph })
    }

    @Test
    fun `a checkbox in a numbered list keeps its number`() {
        val task = Markdown.parse("1. [ ] numbered and undone\n").filterIsInstance<MdBlock.Task>().single()
        assertEquals(1, task.number)
        assertEquals("numbered and undone", task.text)
        assertTrue(!task.checked)
    }

    @Test
    fun `trailing hashes are not part of a heading`() {
        assertEquals("Title", Markdown.parse("## Title ##\n")
            .filterIsInstance<MdBlock.Heading>().single().text)
    }

    @Test
    fun `two spaces at the end of a line break it`() {
        val text = Markdown.parse("first line  \nsecond line\n")
            .filterIsInstance<MdBlock.Paragraph>().single().text
        assertEquals("first line\nsecond line", text)
    }

    @Test
    fun `a rule is a rule and a quote is a quote`() {
        val blocks = Markdown.parse("---\n> quoted\n")
        assertTrue(blocks.first() is MdBlock.Rule)
        assertEquals("quoted", blocks.filterIsInstance<MdBlock.Quote>().single().text)
    }
}
