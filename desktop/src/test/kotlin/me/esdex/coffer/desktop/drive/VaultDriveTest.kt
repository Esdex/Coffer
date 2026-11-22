package me.esdex.coffer.desktop.drive

import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.sync.VaultTree
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The drive mounted for real with FUSE, read back through the kernel with java.nio, which shares nothing
 * with [VaultDrive]: what a file manager would see. The vault behind it is made up.
 */
class VaultDriveTest {
    private val big = Random(1).nextBytes(300_000)
    private val small = "hello\n".toByteArray()
    private val bigId = Id.of(ByteArray(16) { 1 })
    private val smallId = Id.of(ByteArray(16) { 2 })
    private val folderId = Id.of(ByteArray(16) { 3 })
    private val opened = AtomicInteger()
    private val closed = AtomicInteger()

    private lateinit var at: Path
    private lateinit var mounted: VaultDrive.Mounted

    @Before
    fun mount() {
        assumeTrue("no FUSE here", File("/dev/fuse").exists())
        at = Files.createTempDirectory(Path.of("build").toAbsolutePath().also(Files::createDirectories), "drive")
        val tree = DriveTree.of(
            VaultTree(mapOf(folderId to LocalFolder("Docs", null)), mapOf(bigId to meta("big.bin", folderId), smallId to meta("small.txt", null))),
            mapOf(bigId to indexed(bigId, big.size), smallId to indexed(smallId, small.size)),
        )
        val contents = mapOf(bigId to big, smallId to small)
        mounted = VaultDrive.mount(at, tree = { tree }, open = { file ->
            opened.incrementAndGet()
            val bytes = contents.getValue(file.fileId)
            OpenFile(
                read = { position, buffer, offset, length ->
                    if (position >= bytes.size) -1 else {
                        val n = minOf(length.toLong(), bytes.size - position).toInt()
                        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                        n
                    }
                },
                onClose = { closed.incrementAndGet() },
            )
        })
    }

    @After
    fun unmount() {
        if (::mounted.isInitialized) {
            mounted.close()
            Files.deleteIfExists(at)
        }
    }

    @Test
    fun foldersFilesAndSizesShowAsTheVaultHasThem() {
        assertEquals(setOf("Docs", "small.txt"), Files.list(at).use { s -> s.map { it.fileName.toString() }.toList().toSet() })
        assertTrue(Files.isDirectory(at.resolve("Docs")))
        assertEquals(big.size.toLong(), Files.size(at.resolve("Docs/big.bin")))
        assertEquals(small.size.toLong(), Files.size(at.resolve("small.txt")))
        assertEquals(1_700_000_000_000L, Files.getLastModifiedTime(at.resolve("small.txt")).toMillis())
    }

    @Test
    fun filesReadWholeAndFromTheMiddle() {
        assertArrayEquals(big, Files.readAllBytes(at.resolve("Docs/big.bin")))
        assertArrayEquals(small, Files.readAllBytes(at.resolve("small.txt")))
        Files.newByteChannel(at.resolve("Docs/big.bin")).use { channel ->
            channel.position(123_457)
            val buffer = ByteBuffer.allocate(1000)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            assertArrayEquals(big.copyOfRange(123_457, 124_457), buffer.array())
        }
    }

    @Test
    fun everyFileOpenedIsLetGo() {
        Files.readAllBytes(at.resolve("small.txt"))
        Files.readAllBytes(at.resolve("Docs/big.bin"))
        // Release reaches the drive after the reader is closed, on FUSE's own thread.
        val deadline = System.currentTimeMillis() + 5_000
        while (closed.get() < opened.get() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(opened.get() >= 2)
        assertEquals(opened.get(), closed.get())
    }

    @Test
    fun writingIsRefusedAsReadOnly() {
        assertReadOnly { Files.write(at.resolve("new.txt"), small) }
        assertReadOnly { Files.createDirectory(at.resolve("New folder")) }
        assertReadOnly { Files.newOutputStream(at.resolve("small.txt"), StandardOpenOption.APPEND).use { it.write(1) } }
        assertReadOnly { Files.delete(at.resolve("small.txt")) }
        assertReadOnly { Files.move(at.resolve("small.txt"), at.resolve("moved.txt")) }
        assertArrayEquals(small, Files.readAllBytes(at.resolve("small.txt")))
    }

    private fun assertReadOnly(write: () -> Unit) {
        try {
            write()
            fail("written")
        } catch (e: FileSystemException) {
            assertTrue(e.toString(), "Read-only file system" in e.reason.orEmpty())
        }
    }

    private fun meta(name: String, folder: Id?) = FileMeta(
        name, folder, "application/octet-stream", 1_600_000_000_000, 1_700_000_000_000,
        Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES)), Bytes.of(ByteArray(CofferFormat.WRAPPED_KEY_BYTES)),
    )

    private fun indexed(id: Id, size: Int) = IndexedFile(id, size.toLong(), 1, ByteArray(0), FileStatus.READY, emptyMap())
}
