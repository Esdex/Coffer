package me.esdex.coffer.feature.browser.viewer.text

/**
 * The Markdown the text viewer renders, Arcanum's parser: a small dialect, what people put in notes,
 * written here rather than taken from a library for the reason the colouring is. Anything it does not
 * know is left as the text it is, never swallowed.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val text: String, val language: String?) : MdBlock
    data class Quote(val text: String) : MdBlock
    /** [number] is null for a bullet, or the number written for an ordered item. */
    data class Item(val text: String, val indent: Int, val number: Int?) : MdBlock
    /** [number] is set when the checkbox sits in a numbered list rather than a bulleted one. */
    data class Task(val text: String, val checked: Boolean, val indent: Int, val number: Int? = null) : MdBlock

    /** The `key: value` block some notes open with, between two lines of three dashes. */
    data class FrontMatter(val entries: List<Pair<String, String>>) : MdBlock
    /**
     * A table, the one block with a shape of its own.
     *
     * [aligns] is one entry per column, taken from the colons in the line under the header:
     * -1 left, 0 centred, 1 right.
     */
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val aligns: List<Int>
    ) : MdBlock

    data object Rule : MdBlock
}

object Markdown {

    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val TASK    = Regex("^(\\s*)(?:[-*+]|(\\d{1,9})[.)])\\s+\\[([ xX])]\\s*(.*)$")
    private val BULLET  = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val RULE    = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val FENCE   = Regex("^\\s*```\\s*(\\S*)\\s*$")
    private val QUOTE   = Regex("^\\s*>\\s?(.*)$")
    /** The line under a table's header, which is what makes a row of pipes a table. */
    private val DIVIDER = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(?:\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

    /**
     * The source as blocks, in order.
     *
     * Paragraphs gather the lines around them, because Markdown treats a single newline as a
     * space and only a blank line as a break - a note written in one long wrapped paragraph
     * should read as one paragraph, not as a column of short lines.
     */
    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        var index = 0
        var lastWasHardBreak = false

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(paragraph.toString().trim())
                paragraph.setLength(0)
            }
        }

        val lines = source.split("\n")

        // Front matter, if the file opens with it: three dashes, key and value lines, three
        // dashes. Without this the block reads as a rule, a paragraph and another rule, which
        // is how a note written in Obsidian or for a static site would have opened.
        if (lines.firstOrNull()?.trim() == "---") {
            val closing = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (closing != null && closing > 1) {
                val entries = (1 until closing).mapNotNull { i ->
                    val line = lines[i]
                    val colon = line.indexOf(':')
                    if (colon <= 0) null
                    else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
                }
                if (entries.isNotEmpty()) {
                    blocks += MdBlock.FrontMatter(entries)
                    index = closing + 1
                }
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            index++

            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flushParagraph()
                val language = fence.groupValues[1].takeIf { it.isNotBlank() }
                val body = StringBuilder()
                while (index < lines.size && FENCE.matchEntire(lines[index]) == null) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[index])
                    index++
                }
                if (index < lines.size) index++ // the closing fence, if there is one
                blocks += MdBlock.Code(body.toString(), language)
                continue
            }

            // A table announces itself with the line under its header: a row of pipes on its
            // own is just text, and treating it as a table would swallow it.
            if (line.contains('|') && index < lines.size && DIVIDER.matches(lines[index]) &&
                lines[index].contains('-')) {
                flushParagraph()
                val header = cells(line)
                val aligns = cells(lines[index]).map { spec ->
                    val left = spec.startsWith(":")
                    val right = spec.endsWith(":")
                    when {
                        left && right -> 0
                        right         -> 1
                        else          -> -1
                    }
                }
                index++
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                    rows += cells(lines[index])
                    index++
                }
                blocks += MdBlock.Table(header, rows, aligns)
                continue
            }

            when {
                line.isBlank() -> flushParagraph()

                RULE.matches(line) -> { flushParagraph(); blocks += MdBlock.Rule }

                HEADING.matches(line) -> {
                    flushParagraph()
                    val m = HEADING.find(line)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                }

                TASK.matches(line) -> {
                    flushParagraph()
                    val m = TASK.find(line)!!
                    blocks += MdBlock.Task(
                        text = m.groupValues[4].trim(),
                        checked = m.groupValues[3] != " ",
                        indent = m.groupValues[1].length / 2,
                        number = m.groupValues[2].toIntOrNull(),
                    )
                }

                BULLET.matches(line) -> {
                    flushParagraph()
                    val m = BULLET.find(line)!!
                    blocks += MdBlock.Item(m.groupValues[2].trim(), m.groupValues[1].length / 2, null)
                }

                ORDERED.matches(line) -> {
                    flushParagraph()
                    val m = ORDERED.find(line)!!
                    blocks += MdBlock.Item(
                        text   = m.groupValues[3].trim(),
                        indent = m.groupValues[1].length / 2,
                        number = m.groupValues[2].toIntOrNull() ?: 1
                    )
                }

                QUOTE.matches(line) -> {
                    flushParagraph()
                    blocks += MdBlock.Quote(QUOTE.find(line)!!.groupValues[1].trim())
                }

                else -> {
                    val last = blocks.lastOrNull()
                    if (paragraph.isEmpty() && (last is MdBlock.Item || last is MdBlock.Task)) {
                        // A wrapped list item: the second line belongs to the item above it,
                        // not to a paragraph of its own. Markdown calls this lazy continuation
                        // and people write it without knowing they have.
                        blocks[blocks.size - 1] = when (last) {
                            is MdBlock.Item -> last.copy(text = last.text + " " + line.trim())
                            is MdBlock.Task -> last.copy(text = last.text + " " + line.trim())
                            else            -> last
                        }
                    } else {
                        // Two spaces at the end of a line, or a backslash, is Markdown for
                        // "break here": the paragraph keeps the newline instead of a space.
                        val hardBreak = line.endsWith("  ") || line.endsWith("\\")
                        if (paragraph.isNotEmpty()) paragraph.append(if (lastWasHardBreak) '\n' else ' ')
                        paragraph.append(line.trim().removeSuffix("\\").trimEnd())
                        lastWasHardBreak = hardBreak
                    }
                }
            }
        }
        flushParagraph()
        return blocks
    }

    /** The cells of one row, with the outer pipes dropped and each cell trimmed. */
    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
}
