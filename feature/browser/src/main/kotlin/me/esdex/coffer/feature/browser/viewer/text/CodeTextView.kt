package me.esdex.coffer.feature.browser.viewer.text

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.OverScroller
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.abs

/**
 * A text file on screen, Arcanum's CodeEditText made read-only (pre-release item 5): select and copy,
 * no caret and no keyboard. A platform text view rather than Compose text, because Compose lays out
 * the whole text at once on the main thread, which froze Arcanum for seconds on a 250 KB log and then
 * crashed it; the platform's DynamicLayout is what a file of thousands of lines needs.
 *
 * Three things are drawn here, each only for the rows on screen: the line numbers, counting lines of
 * the file (with wrapping on, a line covering several rows is numbered once); the dots and arrows for
 * spaces and tabs; and the colouring, as spans over a window around the screen.
 */
@SuppressLint("ViewConstructor")
class CodeTextView(context: Context) : EditText(context) {

    var showLineNumbers: Boolean = true
        set(value) {
            field = value
            updateGutterPadding()
            invalidate()
        }

    var showWhitespace: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var syntax: Syntax = Syntax.NONE
        set(value) {
            if (field == value) return
            field = value
            scheduleHighlight()
        }

    /** Colour per [TokenKind], by ordinal. */
    var palette: IntArray = IntArray(TokenKind.entries.size)
        set(value) {
            if (field.contentEquals(value)) return
            field = value
            scheduleHighlight()
        }

    /**
     * The selection and its handles. The platform paints them from the Context's theme, which for a
     * view inside Compose is not the app's, so they take the screen's primary colour from here.
     */
    var accentColor: Int = 0
        set(value) {
            if (field == value) return
            field = value
            highlightColor = ColorUtils.setAlphaComponent(value, SELECTION_ALPHA)
            tinted(textSelectHandle, value)?.let { setTextSelectHandle(it) }
            tinted(textSelectHandleLeft, value)?.let { setTextSelectHandleLeft(it) }
            tinted(textSelectHandleRight, value)?.let { setTextSelectHandleRight(it) }
            invalidate()
        }

    var gutterTextColor: Int = 0x80FFFFFF.toInt()
        set(value) {
            field = value
            numberPaint.color = value
            invalidate()
        }

    var gutterBackground: Int = 0
        set(value) {
            field = value
            gutterPaint.color = value
            invalidate()
        }

    /** The panel behind a Markdown file's front matter. */
    var frontMatterBackground: Int = 0
        set(value) {
            if (field == value) return
            field = value
            scheduleHighlight()
        }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterPaint = Paint()
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    /** Visual row -> line number of the file, or 0 for a row that is the wrap of one. */
    private var rowNumbers = IntArray(0)
    private var rowNumbersDirty = true
    private var gutterWidth = 0

    private val highlightPass = Runnable { highlightWindow() }

    // The TextView constructor calls the overrides below before this class's own fields exist.
    private var ready = false

    init {
        // Filling its parent: Compose adds a view as WRAP_CONTENT, and a text view wrapping its width
        // lays out again and again (Arcanum #183).
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        gravity = Gravity.TOP or Gravity.START
        background = null
        includeFontPadding = false
        setHorizontallyScrolling(false)
        // Read, select, copy; nothing to type into, no caret and no keyboard. No key listener is what
        // makes the text not editable; making it selectable turns focus back on, for the selection.
        showSoftInputOnFocus = false
        keyListener = null
        setTextIsSelectable(true)
        isCursorVisible = false
        ready = true
        updateGutterPadding()
    }

    /** Puts the file in, once. */
    fun setDocument(text: String) {
        setText(text, BufferType.EDITABLE)
        rowNumbersDirty = true
        scheduleHighlight()
    }

    // TextView has no getter for this below API 34, so it is kept here; it decides whether a long line
    // wraps or scrolls sideways.
    private var wrapping = true

    fun setWordWrap(wrap: Boolean) {
        if (wrap == wrapping) return
        wrapping = wrap
        setHorizontallyScrolling(!wrap)
        rowNumbersDirty = true
        requestLayout()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        if (ready) rowNumbersDirty = true
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // Scrolling brings text into view that was never coloured: the window follows.
        scheduleHighlight(HIGHLIGHT_DELAY_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rowNumbersDirty = true
        scheduleHighlight()
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout != null && (showLineNumbers || showWhitespace)) {
            if (rowNumbersDirty) rebuildRowNumbers()
            val first = layout.getLineForVertical(scrollY)
            val last = layout.getLineForVertical(scrollY + height)
            if (showLineNumbers) drawNumbers(canvas, first, last)
            if (showWhitespace) drawWhitespace(canvas, first, last)
        }
        super.onDraw(canvas)
    }

    private fun drawNumbers(canvas: Canvas, first: Int, last: Int) {
        // Pinned to the left edge of what is visible, so it stays put while the text scrolls sideways.
        canvas.drawRect(scrollX.toFloat(), scrollY.toFloat(), (scrollX + gutterWidth).toFloat(), (scrollY + height).toFloat(), gutterPaint)
        for (row in first..last) {
            val number = rowNumbers.getOrNull(row) ?: continue
            if (number == 0) continue
            val baseline = getLineBounds(row, bounds)
            val label = number.toString()
            val x = scrollX + gutterWidth - NUMBER_GAP_DP * resources.displayMetrics.density - numberPaint.measureText(label)
            canvas.drawText(label, x, baseline.toFloat(), numberPaint)
        }
    }

    /** A dot for a space, an arrow for a tab, where the character sits; only over the rows on screen. */
    private fun drawWhitespace(canvas: Canvas, first: Int, last: Int) {
        val layout = layout ?: return
        val text = text ?: return
        markPaint.color = gutterTextColor
        markPaint.textSize = textSize * 0.9f
        val left = compoundPaddingLeft
        for (row in first..last) {
            val start = layout.getLineStart(row)
            val end = layout.getLineEnd(row)
            val baseline = getLineBounds(row, bounds)
            for (i in start until minOf(end, text.length)) {
                val c = text[i]
                if (c != ' ' && c != '\t') continue
                canvas.drawText(if (c == ' ') "·" else "→", layout.getPrimaryHorizontal(i) + left, baseline.toFloat(), markPaint)
            }
        }
    }

    /** Which file line each row belongs to, built once per layout: counting per frame costs the file above. */
    private fun rebuildRowNumbers() {
        val layout = layout ?: return
        val text = text ?: return
        val rows = layout.lineCount
        if (rowNumbers.size < rows) rowNumbers = IntArray(rows)
        var fileLine = 1
        for (row in 0 until rows) {
            val start = layout.getLineStart(row)
            val isFileLineStart = start == 0 || (start <= text.length && text[start - 1] == '\n')
            rowNumbers[row] = if (isFileLineStart) fileLine++ else 0
        }
        for (row in rows until rowNumbers.size) rowNumbers[row] = 0
        rowNumbersDirty = false
        updateGutterPadding(fileLine - 1)
    }

    private fun updateGutterPadding(lines: Int = -1) {
        if (!showLineNumbers) {
            if (gutterWidth != 0) {
                gutterWidth = 0
                applyPadding()
            } else if (paddingLeft == 0) {
                applyPadding()
            }
            return
        }
        numberPaint.textSize = textSize
        numberPaint.typeface = typeface
        numberPaint.color = gutterTextColor
        val digits = maxOf(2, (if (lines > 0) lines else 99).toString().length)
        val width = (numberPaint.measureText("8".repeat(digits)) + NUMBER_GAP_DP * 2 * resources.displayMetrics.density).toInt()
        if (width != gutterWidth) {
            gutterWidth = width
            applyPadding()
        }
    }

    private fun applyPadding() {
        val pad = (8 * resources.displayMetrics.density).toInt()
        setPadding(gutterWidth + pad, pad, pad, pad)
        requestLayout()
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        if (!ready) return
        rowNumbersDirty = true
        updateGutterPadding()
    }

    override fun setTypeface(tf: Typeface?) {
        super.setTypeface(tf)
        if (!ready) return
        rowNumbersDirty = true
        updateGutterPadding()
    }

    // ── Scrolling ────────────────────────────────────────────────────────────
    /*
     * A text view scrolls while a finger drags it and stops dead when it lifts. Everything else in the
     * app glides, so the release is ours: the finger's speed goes to a scroller. And a drag that is
     * clearly sideways, with wrapping off, is taken over here and moves the text by hand; left to the
     * platform it could start a selection instead.
     */
    private val scroller = OverScroller(context)
    private var tracker: VelocityTracker? = null
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var panningSideways = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) scroller.forceFinished(true)
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downX = event.x
                downY = event.y
                lastX = event.x
                panningSideways = false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (!panningSideways && maxScrollX() > 0) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // Decided once, and only for a drag clearly sideways; one merely not vertical stays the platform's.
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                        panningSideways = true
                        lastX = event.x
                        parent?.requestDisallowInterceptTouchEvent(true)
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (panningSideways) {
                    val dx = lastX - event.x
                    lastX = event.x
                    scrollTo((scrollX + dx.toInt()).coerceIn(0, maxScrollX()), scrollY)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val panned = panningSideways
                tracker?.let { t ->
                    t.addMovement(event)
                    t.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    fling((-t.xVelocity).toInt(), (-t.yVelocity).toInt())
                }
                releaseTracker()
                panningSideways = false
                if (panned) return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseTracker()
                panningSideways = false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
    }

    private fun fling(velocityX: Int, velocityY: Int) {
        val maxY = maxScrollY()
        val maxX = maxScrollX()
        val vy = if (maxY > 0 && abs(velocityY) > minFlingVelocity) velocityY else 0
        val vx = if (maxX > 0 && abs(velocityX) > minFlingVelocity) velocityX else 0
        if (vx == 0 && vy == 0) return
        scroller.fling(scrollX, scrollY, vx, vy, 0, maxX, 0, maxY)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        scrollTo(scroller.currX.coerceIn(0, maxScrollX()), scroller.currY.coerceIn(0, maxScrollY()))
        postInvalidateOnAnimation()
    }

    private fun maxScrollY(): Int {
        val l = layout ?: return 0
        return maxOf(0, l.height + paddingTop + paddingBottom - height)
    }

    private fun maxScrollX(): Int {
        val l = layout ?: return 0
        if (wrapping) return 0
        return maxOf(0, l.width + compoundPaddingLeft + compoundPaddingRight - width)
    }

    // A mutated wrap, so the tint cannot reach the shared state and colour every other text field.
    private fun tinted(drawable: Drawable?, color: Int): Drawable? = drawable?.let { DrawableCompat.wrap(it.mutate()).also { d -> DrawableCompat.setTint(d, color) } }

    // ── Colouring ────────────────────────────────────────────────────────────

    private fun scheduleHighlight(delayMs: Long = 0) {
        if (!ready) return
        removeCallbacks(highlightPass)
        postDelayed(highlightPass, delayMs)
    }

    /**
     * Colours whole lines a screenful either side of what is on screen, so a scroll finds most of it
     * done. Only our own spans are cleared; a selection or anything the platform put there stays.
     */
    private fun highlightWindow() {
        val editable = text ?: return
        val layout = layout ?: return
        if (syntax == Syntax.NONE) {
            clearOurSpans(editable, 0, editable.length)
            return
        }
        val firstRow = layout.getLineForVertical(scrollY - height)
        val lastRow = layout.getLineForVertical(scrollY + height * 2)
        val from = layout.getLineStart(firstRow)
        val to = minOf(layout.getLineEnd(lastRow), editable.length)
        if (to <= from) return

        // A Markdown file's front matter is its properties, not prose: coloured by its own rules, and
        // the generic ones kept out of it.
        val frontEnd = if (syntax == Syntax.MARKDOWN) frontMatterEnd(editable) else 0
        clearOurSpans(editable, from, to)
        if (frontEnd > from) paintFrontMatter(editable, minOf(frontEnd, editable.length))
        for (token in SyntaxHighlighter.tokens(editable, from, to, syntax)) {
            if (token.start < frontEnd) continue
            val color = palette.getOrNull(token.kind.ordinal) ?: continue
            editable.setSpan(HlColor(color), token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val style = when (token.kind) {
                TokenKind.HEADING, TokenKind.KEYWORD, TokenKind.EMPHASIS -> Typeface.BOLD
                TokenKind.COMMENT -> Typeface.ITALIC
                else -> null
            }
            if (style != null) editable.setSpan(HlStyle(style), token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun clearOurSpans(editable: Editable, from: Int, to: Int) {
        editable.getSpans(from, to, HlColor::class.java).forEach { editable.removeSpan(it) }
        editable.getSpans(from, to, HlStyle::class.java).forEach { editable.removeSpan(it) }
        editable.getSpans(from, to, HlBackground::class.java).forEach { editable.removeSpan(it) }
    }

    /** Where the front matter ends, or 0: only at the very start, and only when it closes. */
    private fun frontMatterEnd(text: CharSequence): Int {
        if (text.length < 8) return 0
        var i = 0
        while (i < text.length && text[i] != '\n') i++
        if (text.subSequence(0, i).toString().trim() != "---") return 0
        var lineStart = i + 1
        while (lineStart < text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            if (text.subSequence(lineStart, lineEnd).toString().trim() == "---") return lineEnd
            lineStart = lineEnd + 1
        }
        return 0
    }

    /** The block itself: a panel behind it, muted fences, keys apart from their values. */
    private fun paintFrontMatter(editable: Editable, end: Int) {
        if (frontMatterBackground != 0) editable.setSpan(HlBackground(frontMatterBackground), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val comment = palette.getOrNull(TokenKind.COMMENT.ordinal) ?: return
        val key = palette.getOrNull(TokenKind.ATTR.ordinal) ?: return
        val value = palette.getOrNull(TokenKind.STRING.ordinal) ?: return
        var lineStart = 0
        while (lineStart < end) {
            var lineEnd = lineStart
            while (lineEnd < end && editable[lineEnd] != '\n') lineEnd++
            val line = editable.subSequence(lineStart, lineEnd).toString()
            if (line.trim() == "---") {
                editable.setSpan(HlColor(comment), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    editable.setSpan(HlColor(key), lineStart, lineStart + colon, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    editable.setSpan(HlStyle(Typeface.BOLD), lineStart, lineStart + colon, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (lineStart + colon + 1 < lineEnd) editable.setSpan(HlColor(value), lineStart + colon + 1, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            lineStart = lineEnd + 1
        }
    }

    /** Ours, so they can be found and removed without touching anyone else's. */
    private class HlColor(color: Int) : ForegroundColorSpan(color)

    private class HlStyle(style: Int) : StyleSpan(style)

    /** Fills the whole width of each line it covers, which is what makes it read as a panel. */
    private class HlBackground(private val color: Int) : LineBackgroundSpan {
        override fun drawBackground(canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int) {
            val previous = paint.color
            paint.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = previous
        }
    }

    private companion object {
        const val HIGHLIGHT_DELAY_MS = 120L
        const val NUMBER_GAP_DP = 8f
        const val SELECTION_ALPHA = 0x50
    }
}
