package me.esdex.coffer.feature.browser.preview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** The stream previews read vault files through; the oracle is the source array itself. */
class ReaderInputStreamTest {
    // More than two of the stream's 64 KiB buffers, and not a multiple of them.
    private val source = Random(3).nextBytes(150_001)

    /** Gives back at most [most] bytes a call, as the vault reader does at a chunk's end. */
    private fun stream(failed: AtomicBoolean = AtomicBoolean(), most: Int = 40_000, fail: (Long) -> Boolean = { false }) =
        ReaderInputStream(source.size.toLong(), failed) { at, into, offset, length ->
            if (fail(at)) throw IllegalStateException("offline")
            if (at >= source.size) -1 else {
                val n = minOf(length, most, source.size - at.toInt())
                source.copyInto(into, offset, at.toInt(), at.toInt() + n)
                n
            }
        }

    @Test
    fun readsTheWholeFileInAnyStepsAndThenEnds() {
        val random = Random(5)
        val out = java.io.ByteArrayOutputStream()
        stream().use { s ->
            val buffer = ByteArray(70_000)
            while (true) {
                if (random.nextInt(4) == 0) {
                    val b = s.read()
                    if (b < 0) break
                    out.write(b)
                } else {
                    val n = s.read(buffer, 3, random.nextInt(1, 70_000 - 3))
                    if (n < 0) break
                    out.write(buffer, 3, n)
                }
            }
            assertEquals(-1, s.read())
            assertEquals(0, s.read(ByteArray(1), 0, 0))
        }
        assertArrayEquals(source, out.toByteArray())
    }

    @Test
    fun resetGoesBackToTheMarkFromAnotherBuffer() {
        stream().use { s ->
            s.skip(10)
            s.mark(0)
            val first = ByteArray(100_000).also { readFully(s, it) }
            s.reset()
            val again = ByteArray(100_000).also { readFully(s, it) }
            assertArrayEquals(first, again)
            assertArrayEquals(source.copyOfRange(10, 100_010), again)
        }
    }

    @Test
    fun skipMovesWithoutReadingAndStopsAtTheEnd() {
        var reads = 0
        val s = ReaderInputStream(source.size.toLong(), AtomicBoolean()) { at, into, offset, length ->
            reads++
            val n = minOf(length, source.size - at.toInt())
            source.copyInto(into, offset, at.toInt(), at.toInt() + n)
            n
        }
        assertEquals(120_000L, s.skip(120_000))
        assertEquals(0, reads)
        assertEquals(source[120_000].toInt() and 0xFF, s.read())
        assertEquals(30_000, s.available())
        assertEquals(30_000L, s.skip(1_000_000))
        assertEquals(-1, s.read())
    }

    @Test
    fun aFailedReadIsToldApartFromTheEnd() {
        val failed = AtomicBoolean()
        // Whole buffers, so the next read past the first one goes to the source.
        val s = stream(failed, most = Int.MAX_VALUE) { it >= 65_536 }
        readFully(s, ByteArray(65_536))
        assertFalse(failed.get())
        try {
            s.read()
            fail("the read past the first buffer fails")
        } catch (_: IOException) {
        }
        assertTrue(failed.get())
    }

    private fun readFully(s: java.io.InputStream, into: ByteArray) {
        var at = 0
        while (at < into.size) {
            val n = s.read(into, at, into.size - at)
            check(n > 0)
            at += n
        }
    }
}
