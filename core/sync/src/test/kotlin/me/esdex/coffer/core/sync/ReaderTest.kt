package me.esdex.coffer.core.sync

import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.crypto.AuthenticationException
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.Progress
import me.esdex.coffer.core.storage.StorageBackend
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random

/**
 * Reading any range of a vault file without decrypting it whole (viewers, pre-release item 5).
 * The oracle is the source itself: byte p of a [PatternSource] is p mod 251.
 */
class ReaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud()

    private fun device() = Device(cloud, keysOf(master), temp.newFolder())

    private fun expected(position: Long, length: Int) = ByteArray(length) { ((position + it) % 251).toByte() }

    private suspend fun readerOf(here: Device, fileId: Id, storage: StorageBackend = here.storage): VaultFileReader {
        val file = here.index.file(fileId)!!
        return here.keys.reader(storage, file, here.keys.transferKeys(fileId, file.sealedMeta)!!)
    }

    @Test
    fun anyRangeReadsTheBytesOfTheSource() = runBlocking {
        val size = 3L * CofferFormat.CHUNK_SIZE + 12_345
        val here = device()
        val fileId = here.upload("clip.mp4", PatternSource(size))
        readerOf(here, fileId).use { reader ->
            assertEquals(size, reader.size)
            val random = Random(7)
            val ranges = listOf(
                0L to 100,
                CofferFormat.CHUNK_SIZE - 10L to 20, // across a chunk boundary
                size - 50 to 50, // the very end
                size - 50 to 500, // asked past the end: only what there is
            ) + List(30) { random.nextLong(size) to random.nextInt(1, 300_000) }
            for ((position, length) in ranges) {
                val buffer = ByteArray(length + 8)
                val n = reader.read(position, buffer, 4, length)
                val want = minOf(length.toLong(), size - position).toInt()
                assertEquals("bytes at $position", want, n)
                assertArrayEquals("bytes at $position", expected(position, want), buffer.copyOfRange(4, 4 + want))
            }
            assertEquals(-1, reader.read(size, ByteArray(1), 0, 1))
        }
    }

    @Test
    fun aRangeAcrossTwoPartsReadsFromBoth() = runBlocking {
        val size = CofferFormat.CHUNKS_PER_PART.toLong() * CofferFormat.CHUNK_SIZE + 5
        val here = device()
        val fileId = here.upload("big.bin", PatternSource(size))
        readerOf(here, fileId).use { reader ->
            val boundary = CofferFormat.CHUNKS_PER_PART.toLong() * CofferFormat.CHUNK_SIZE
            val buffer = ByteArray(10)
            assertEquals(10, reader.read(boundary - 5, buffer, 0, 10))
            assertArrayEquals(expected(boundary - 5, 10), buffer)
            assertEquals(2, reader.partsUsed().size)
        }
    }

    @Test
    fun aChunkReadTwiceIsFetchedOnce() = runBlocking {
        val here = device()
        val fileId = here.upload("a.bin", PatternSource(2L * CofferFormat.CHUNK_SIZE))
        var opened = 0
        val counting = object : StorageBackend by here.storage {
            override suspend fun openBlob(id: MessageId, offset: Long, length: Long, onProgress: (Progress) -> Unit): InputStream {
                opened++
                return here.storage.openBlob(id, offset, length, onProgress)
            }
        }
        readerOf(here, fileId, counting).use { reader ->
            repeat(5) { reader.read(it * 1000L, ByteArray(100), 0, 100) }
            assertEquals(1, opened)
            reader.read(CofferFormat.CHUNK_SIZE + 1L, ByteArray(100), 0, 100)
            assertEquals(2, opened)
        }
    }

    @Test
    fun aTamperedChunkIsRefused() = runBlocking {
        val here = device()
        val fileId = here.upload("a.bin", PatternSource(2L * CofferFormat.CHUNK_SIZE))
        // Telegram, or anyone with the account, hands back the right length with one byte changed.
        val tampering = object : StorageBackend by here.storage {
            override suspend fun openBlob(id: MessageId, offset: Long, length: Long, onProgress: (Progress) -> Unit): InputStream {
                val bytes = here.storage.openBlob(id, offset, length, onProgress).readBytes()
                bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
                return ByteArrayInputStream(bytes)
            }
        }
        readerOf(here, fileId, tampering).use { reader ->
            try {
                reader.read(10, ByteArray(10), 0, 10)
                fail("a changed chunk must not decrypt")
            } catch (_: AuthenticationException) {
            }
        }
    }

    @Test
    fun chunksSwappedInPlaceAreRefused() = runBlocking {
        val here = device()
        val fileId = here.upload("a.bin", PatternSource(3L * CofferFormat.CHUNK_SIZE))
        // Chunk 1 served where chunk 0 is asked for: each is bound to its index, so this fails.
        val swapping = object : StorageBackend by here.storage {
            override suspend fun openBlob(id: MessageId, offset: Long, length: Long, onProgress: (Progress) -> Unit): InputStream =
                here.storage.openBlob(id, if (offset == 0L) CofferFormat.ENCRYPTED_CHUNK_SIZE.toLong() else offset, length, onProgress)
        }
        readerOf(here, fileId, swapping).use { reader ->
            try {
                reader.read(0, ByteArray(10), 0, 10)
                fail("a chunk from another place must not decrypt")
            } catch (_: AuthenticationException) {
            }
        }
    }

    @Test
    fun aFileWithAMissingPartIsNotOpened() = runBlocking {
        val here = device()
        val fileId = here.upload("a.bin", PatternSource(100))
        val file = here.index.file(fileId)!!
        val incomplete = me.esdex.coffer.core.index.IndexedFile(file.fileId, file.plainSize, file.partCount, file.sealedMeta, file.status, emptyMap())
        try {
            here.keys.reader(here.storage, incomplete, here.keys.transferKeys(fileId, file.sealedMeta)!!)
            fail("a file without its parts cannot be read")
        } catch (_: MissingPartException) {
        }
    }

    @Test
    fun aClosedReaderReadsNoMore() = runBlocking {
        val here = device()
        val fileId = here.upload("a.bin", PatternSource(100))
        val reader = readerOf(here, fileId)
        reader.read(0, ByteArray(10), 0, 10)
        reader.close()
        try {
            reader.read(0, ByteArray(10), 0, 10)
            fail("closed")
        } catch (_: IllegalStateException) {
        }
    }
}
