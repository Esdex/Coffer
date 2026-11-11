package me.esdex.coffer.feature.browser.preview

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A file read front to back through [readAt], a vault file's reader, with mark and reset for decoders
 * that look at the start twice. Buffered here: EXIF is parsed a byte at a time, and each trip to the
 * reader is a coroutine of its own. A failed read is noted in [failed]: the decoder only sees an
 * IOException, and may take it for a damaged picture.
 */
internal class ReaderInputStream(
    private val size: Long,
    private val failed: AtomicBoolean,
    private val readAt: suspend (position: Long, into: ByteArray, offset: Int, length: Int) -> Int,
) : InputStream() {
    private val buffer = ByteArray(BUFFER)
    private var bufferStart = 0L
    private var bufferLength = 0
    private var position = 0L
    private var marked = 0L

    override fun read(): Int {
        if (!fill()) return -1
        return buffer[(position++ - bufferStart).toInt()].toInt() and 0xFF
    }

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!fill()) return -1
        val at = (position - bufferStart).toInt()
        val n = minOf(length, bufferLength - at)
        buffer.copyInto(into, offset, at, at + n)
        position += n
        return n
    }

    /** Makes the byte at [position] buffered; false at the end. */
    private fun fill(): Boolean {
        if (position >= bufferStart && position < bufferStart + bufferLength) return true
        if (position >= size) return false
        val n = try {
            runBlocking { readAt(position, buffer, 0, buffer.size) }
        } catch (e: Exception) {
            failed.set(true)
            throw IOException(e)
        }
        if (n <= 0) return false
        bufferStart = position
        bufferLength = n
        return true
    }

    override fun skip(n: Long): Long {
        val skipped = n.coerceIn(0, size - position)
        position += skipped
        return skipped
    }

    override fun available(): Int = (size - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    override fun markSupported() = true

    override fun mark(readlimit: Int) {
        marked = position
    }

    override fun reset() {
        position = marked
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
