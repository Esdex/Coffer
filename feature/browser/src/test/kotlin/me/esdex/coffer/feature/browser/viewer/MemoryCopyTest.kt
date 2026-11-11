package me.esdex.coffer.feature.browser.viewer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

/** Copying a vault file whole for the viewers; the oracle is the source array itself. */
class MemoryCopyTest {
    private val source = Random(9).nextBytes(3 * 1_048_576 + 12_345)

    /** Gives back at most [most] bytes a call, as the vault reader does at a chunk's end. */
    private fun reader(most: Int, endsAt: Int = source.size): suspend (Long, ByteArray, Int, Int) -> Int = { at, into, offset, length ->
        if (at >= endsAt) -1 else {
            val n = minOf(length, most, endsAt - at.toInt())
            source.copyInto(into, offset, at.toInt(), at.toInt() + n)
            n
        }
    }

    @Test
    fun everyByteArrivesInOrderWithProgressToTheEnd() = runBlocking {
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        copyWhole(source.size.toLong(), reader(most = 700_001), { bytes, length -> out.write(bytes, 0, length) }) { done, total ->
            assertEquals(source.size.toLong(), total)
            seen += done
        }
        assertArrayEquals(source, out.toByteArray())
        assertEquals(0L, seen.first())
        assertEquals(source.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun aFileThatEndsEarlyIsAnError() = runBlocking {
        try {
            copyWhole(source.size.toLong(), reader(most = 1 shl 20, endsAt = 2_000_000), { _, _ -> })
            fail("short by more than a megabyte")
        } catch (_: IOException) {
        }
    }

    @Test
    fun anEmptyFileCopiesToNothing() = runBlocking {
        var writes = 0
        copyWhole(0, reader(most = 1), { _, _ -> writes++ })
        assertEquals(0, writes)
    }
}
