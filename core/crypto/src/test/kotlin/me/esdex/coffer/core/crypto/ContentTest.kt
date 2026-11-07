package me.esdex.coffer.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

class ContentTest {
    private val sodium = realSodium
    private val chunk = CofferFormat.CHUNK_SIZE
    private val sealedChunk = CofferFormat.ENCRYPTED_CHUNK_SIZE

    private fun encryptAll(plain: ByteArray, dek: SecretKey, fileId: Id): List<ByteArray> {
        val encryptor = ContentEncryptor(sodium, dek, fileId, plain.size.toLong())
        val input = ByteArrayInputStream(plain)
        return List(encryptor.partCount) { part ->
            ByteArrayOutputStream().also { encryptor.encryptPart(part, input, it) }.toByteArray()
        }
    }

    private fun decryptAll(parts: List<ByteArray>, size: Long, dek: SecretKey, fileId: Id): ByteArray {
        val decryptor = ContentDecryptor(sodium, dek, fileId, size)
        val out = ByteArrayOutputStream()
        parts.forEachIndexed { i, part -> decryptor.decryptPart(i, ByteArrayInputStream(part), out) }
        return out.toByteArray()
    }

    @Test
    fun roundTripAroundChunkBoundaries() {
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 17)) {
            val dek = FileKeys.generateDek(sodium)
            val fileId = Id.random(sodium)
            val plain = pattern(size)
            val parts = encryptAll(plain, dek, fileId)
            assertEquals(1, parts.size)
            assertEquals(ContentLayout.encryptedPartSize(size.toLong(), 0), parts[0].size.toLong())
            assertArrayEquals("size $size", plain, decryptAll(parts, size.toLong(), dek, fileId))
        }
    }

    @Test
    fun emptyFileIsOneEmptyLastChunk() {
        assertEquals(1, ContentLayout.chunkCount(0))
        assertEquals(1, ContentLayout.partCount(0))
        assertEquals(CofferFormat.ENCRYPTED_CHUNK_OVERHEAD.toLong(), ContentLayout.encryptedPartSize(0, 0))
    }

    @Test
    fun exactMultipleOfChunkHasNoEmptyTail() {
        assertEquals(2, ContentLayout.chunkCount(2L * chunk))
        assertEquals(chunk, ContentLayout.chunkPlainSize(2L * chunk, 1))
    }

    @Test
    fun layoutOfLargeFiles() {
        val size = 5L * 1024 * 1024 * 1024 + 7 // 5 GiB + 7 bytes
        assertEquals(5121, ContentLayout.chunkCount(size))
        assertEquals(11, ContentLayout.partCount(size))
        assertEquals(1, ContentLayout.partOfChunk(512))
        assertEquals(0, ContentLayout.offsetInPart(512))
        assertEquals(3L * sealedChunk, ContentLayout.offsetInPart(515))
        assertEquals(512L * sealedChunk, ContentLayout.encryptedPartSize(size, 0))
        assertEquals(5120L..5120L, ContentLayout.chunksOfPart(size, 10))
        assertEquals(7L + CofferFormat.ENCRYPTED_CHUNK_OVERHEAD, ContentLayout.encryptedPartSize(size, 10))
        assertThrows(IllegalArgumentException::class.java) { ContentLayout.chunksOfPart(size, 11) }
        assertThrows(IllegalArgumentException::class.java) { ContentLayout.chunkCount(CofferFormat.MAX_PLAIN_SIZE + 1) }
    }

    /** One file across two parts, streamed: 512 MiB + 1 byte, nothing held in memory. */
    @Test
    fun twoPartsStreamedWithGlobalChunkIndex() {
        val size = 512L * chunk + 1
        val dek = FileKeys.generateDek(sodium)
        val fileId = Id.random(sodium)
        val encryptor = ContentEncryptor(sodium, dek, fileId, size)
        assertEquals(2, encryptor.partCount)

        val source = PatternStream(size)
        val sourceHasher = ContentHasher(sodium)
        val part0 = CountingSink()
        encryptor.encryptPart(0, source, part0.tee(), sourceHasher)
        val part1 = ByteArrayOutputStream()
        encryptor.encryptPart(1, source, part1, sourceHasher)
        assertEquals(512L * sealedChunk, part0.count)
        assertEquals(1L + CofferFormat.ENCRYPTED_CHUNK_OVERHEAD, part1.size().toLong())

        // Part 1 holds chunk 512: decrypting it as chunk 0 of the file must fail.
        val decryptor = ContentDecryptor(sodium, dek, fileId, size)
        val out = ByteArrayOutputStream()
        decryptor.decryptPart(1, ByteArrayInputStream(part1.toByteArray()), out)
        assertArrayEquals(byteArrayOf((512L * chunk % 251).toByte()), out.toByteArray())
        assertThrows(AuthenticationException::class.java) {
            ChunkCipher(sodium, dek, fileId).decrypt(0, true, part1.toByteArray())
        }

        val (expectedHash, _) = ContentHasher.hash(sodium, PatternStream(size))
        assertEquals(expectedHash, sourceHasher.finish())
    }

    @Test
    fun tamperedReorderedOrForeignChunksFail() {
        val dek = FileKeys.generateDek(sodium)
        val fileId = Id.random(sodium)
        val cipher = ChunkCipher(sodium, dek, fileId)
        val c0 = cipher.encrypt(0, false, pattern(100))
        val c1 = cipher.encrypt(1, true, pattern(50))

        for (i in listOf(0, 23, 24, c0.size - 1)) {
            assertThrows(AuthenticationException::class.java) { cipher.decrypt(0, false, c0.flipped(i)) }
        }
        assertThrows(AuthenticationException::class.java) { cipher.decrypt(1, false, c0) } // moved
        assertThrows(AuthenticationException::class.java) { cipher.decrypt(0, true, c1) } // swapped
        assertThrows(AuthenticationException::class.java) { cipher.decrypt(0, true, c0) } // truncation: c0 is not last
        assertThrows(AuthenticationException::class.java) { ChunkCipher(sodium, dek, Id.random(sodium)).decrypt(0, false, c0) }
        assertThrows(AuthenticationException::class.java) {
            ChunkCipher(sodium, FileKeys.generateDek(sodium), fileId).decrypt(0, false, c0)
        }
        assertThrows(AuthenticationException::class.java) { cipher.decrypt(0, false, c0.copyOf(39)) }
    }

    @Test
    fun truncatedOrExtendedPartFails() {
        val dek = FileKeys.generateDek(sodium)
        val fileId = Id.random(sodium)
        val size = 2L * chunk + 10
        val part = encryptAll(pattern(size.toInt()), dek, fileId).single()
        val decryptor = ContentDecryptor(sodium, dek, fileId, size)

        // Last chunk dropped: the stream ends early.
        assertThrows(EOFException::class.java) {
            decryptor.decryptPart(0, ByteArrayInputStream(part.copyOf(2 * sealedChunk)), ByteArrayOutputStream())
        }
        // Same bytes declared as a shorter file: chunk 1 is then the last one and fails its AD.
        assertThrows(AuthenticationException::class.java) {
            ContentDecryptor(sodium, dek, fileId, 2L * chunk)
                .decryptPart(0, ByteArrayInputStream(part.copyOf(2 * sealedChunk)), ByteArrayOutputStream())
        }
        assertThrows(AuthenticationException::class.java) {
            decryptor.decryptPart(0, ByteArrayInputStream(part + byteArrayOf(0)), ByteArrayOutputStream())
        }
        // Chunks 0 and 1 swapped inside the part.
        val swapped = part.copyOfRange(sealedChunk, 2 * sealedChunk) + part.copyOfRange(0, sealedChunk) +
            part.copyOfRange(2 * sealedChunk, part.size)
        assertThrows(AuthenticationException::class.java) {
            decryptor.decryptPart(0, ByteArrayInputStream(swapped), ByteArrayOutputStream())
        }
    }

    @Test
    fun sourceOfWrongSizeIsRejected() {
        val dek = FileKeys.generateDek(sodium)
        val fileId = Id.random(sodium)
        assertThrows(ContentSizeException::class.java) {
            ContentEncryptor(sodium, dek, fileId, 100).encryptPart(0, ByteArrayInputStream(pattern(99)), ByteArrayOutputStream())
        }
        assertThrows(ContentSizeException::class.java) {
            ContentEncryptor(sodium, dek, fileId, 100).encryptPart(0, ByteArrayInputStream(pattern(101)), ByteArrayOutputStream())
        }
    }

    @Test
    fun randomAccessChunk() {
        val dek = FileKeys.generateDek(sodium)
        val fileId = Id.random(sodium)
        val size = 3L * chunk + 5
        val plain = pattern(size.toInt())
        val part = encryptAll(plain, dek, fileId).single()
        val offset = ContentLayout.offsetInPart(2).toInt()
        val chunk2 = ContentDecryptor(sodium, dek, fileId, size).decryptChunk(2, part.copyOfRange(offset, offset + sealedChunk))
        assertArrayEquals(plain.copyOfRange(2 * chunk, 3 * chunk), chunk2)
    }

    /** The oracle's pattern as a stream of any length. */
    private class PatternStream(private val size: Long) : InputStream() {
        private var position = 0L
        override fun read(): Int = if (position >= size) -1 else ((position++ % 251).toInt())
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            val n = minOf(len.toLong(), size - position).toInt()
            for (i in 0 until n) b[off + i] = ((position + i) % 251).toByte()
            position += n
            return n
        }
    }

    private class CountingSink {
        var count = 0L
        fun tee(): OutputStream = object : OutputStream() {
            override fun write(b: Int) { count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { count += len }
        }
    }
}
