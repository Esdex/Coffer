package me.esdex.coffer.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

/** Copies of shared files kept on the phone until uploaded (audit 2, N4). */
class LocalStreamTest {
    private val sodium = realSodium
    private val chunk = CofferFormat.CHUNK_SIZE
    private val sealedChunk = CofferFormat.ENCRYPTED_CHUNK_SIZE

    private fun seal(plain: ByteArray, key: SecretKey, writes: Int = 7): ByteArray {
        val out = ByteArrayOutputStream()
        LocalStream.sealing(sodium, key, out).use { stream ->
            // In uneven pieces, as a copy loop writes.
            var at = 0
            val step = maxOf(1, plain.size / writes + 13)
            while (at < plain.size) {
                val n = minOf(step, plain.size - at)
                stream.write(plain, at, n)
                at += n
            }
        }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, key: SecretKey): ByteArray =
        LocalStream.opening(sodium, key, ByteArrayInputStream(sealed)).use { it.readBytes() }

    @Test
    fun whatIsSealedOpensAsItWasAtAnySize() {
        val key = LocalStream.newKey(sodium)
        for (size in listOf(0, 1, 1000, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 5)) {
            val plain = Random(size).nextBytes(size)
            val sealed = seal(plain, key)
            // One chunk per started MiB; an empty file is one empty chunk, the last.
            val chunks = maxOf(1, (size + chunk - 1) / chunk)
            assertEquals("size $size", size.toLong() + chunks * CofferFormat.ENCRYPTED_CHUNK_OVERHEAD, sealed.size.toLong())
            assertArrayEquals("size $size", plain, open(sealed, key))
        }
    }

    @Test
    fun theSealedFileDoesNotShowItsContent() {
        val key = LocalStream.newKey(sodium)
        val plain = "the content of a shared file, written out in full ".repeat(100).toByteArray()
        val sealed = String(seal(plain, key), Charsets.ISO_8859_1)
        assertFalse(sealed.contains("shared file"))
    }

    @Test
    fun aCopyCutAtAChunkBoundaryDoesNotOpen() {
        val key = LocalStream.newKey(sodium)
        val sealed = seal(Random(1).nextBytes(2 * chunk + 10), key)
        assertThrows(IOException::class.java) { open(sealed.copyOf(sealedChunk), key) }
        assertThrows(IOException::class.java) { open(sealed.copyOf(2 * sealedChunk), key) }
        assertThrows(IOException::class.java) { open(sealed.copyOf(sealed.size - 1), key) }
        assertThrows(IOException::class.java) { open(ByteArray(0), key) }
    }

    @Test
    fun aChangedByteAChunkMovedOrAnotherKeyDoesNotOpen() {
        val key = LocalStream.newKey(sodium)
        val sealed = seal(Random(2).nextBytes(3 * chunk), key)
        val changed = sealed.copyOf().also { it[sealedChunk + 100] = (it[sealedChunk + 100].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { open(changed, key) }
        val swapped = sealed.copyOfRange(sealedChunk, 2 * sealedChunk) + sealed.copyOfRange(0, sealedChunk) + sealed.copyOfRange(2 * sealedChunk, sealed.size)
        assertThrows(IOException::class.java) { open(swapped, key) }
        assertThrows(IOException::class.java) { open(sealed, LocalStream.newKey(sodium)) }
    }
}
