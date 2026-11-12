package me.esdex.coffer.feature.browser.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A double tap on a PDF keeps the point under the finger in place, frame by frame. Checked against the
 * screen as the viewer draws it, not against the formula: a document point (x, y) is at
 * x * scale - sideways across and (y - list) * scale down, the list drawn scaled from its top left.
 */
class PdfZoomTest {

    private class View(var scale: Float, var sideways: Float, var list: Float) {
        fun screenOf(x: Float, y: Float) = Offset(x * scale - sideways, (y - list) * scale)
        fun documentAt(at: Offset) = Offset((at.x + sideways) / scale, at.y / scale + list)

        fun glide(at: Offset, frames: List<Float>) = frames.map { next ->
            val shift = zoomShift(at, sideways, scale, next)
            scale = next
            sideways += shift.x
            list += shift.y
            this
        }
    }

    private fun assertNear(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    @Test
    fun zoomingInKeepsThePointUnderTheFinger() {
        val view = View(scale = 1f, sideways = 0f, list = 500f)
        val finger = Offset(300f, 400f)
        val point = view.documentAt(finger)
        assertNear(Offset(300f, 900f), point)
        view.glide(finger, listOf(1.2f, 1.7f, 2.3f, 2.5f)).forEach { assertNear(finger, it.screenOf(point.x, point.y)) }
        assertEquals(450f, view.sideways, 0.01f)
    }

    @Test
    fun zoomingOutKeepsItToo() {
        val view = View(scale = 2.5f, sideways = 450f, list = 700f)
        val finger = Offset(100f, 600f)
        val point = view.documentAt(finger)
        view.glide(finger, listOf(2f, 1.4f, 1f)).forEach { assertNear(finger, it.screenOf(point.x, point.y)) }
        // Back at full width the page starts at the left edge: the clamp in the viewer puts 120 back to 0.
        assertEquals(120f, view.sideways, 0.01f)
    }

    @Test
    fun aTapAtTheTopLeftCornerMovesNothing() {
        val shift = zoomShift(Offset.Zero, 0f, 1f, 2.5f)
        assertNear(Offset.Zero, shift)
    }
}
