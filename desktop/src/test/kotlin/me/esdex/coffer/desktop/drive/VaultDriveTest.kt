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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The drive mounted for real with FUSE, used through the kernel with java.nio, which shares nothing with
 * [VaultDrive]: what a file manager would see and do. The vault behind it is made up, and changes made on
 * the drive land in it as [VaultWriter][me.esdex.coffer.desktop.vault.VaultWriter] would make them.
 */
class VaultDriveTest {
    private val big = Random(1).nextBytes(300_000)
    private val small = "hello\n".toByteArray()
    private val bigId = id(1)
    private val smallId = id(2)
    private val docsId = id(3)
    private val opened = AtomicInteger()
    private val closed = AtomicInteger()

    // The made-up vault.
    private val folders = mutableMapOf(docsId to LocalFolder("Docs", null))
    private val files = mutableMapOf(bigId to meta("big.bin", docsId), smallId to meta("small.txt", null))
    private val contents = mapOf(bigId to big, smallId to small)
    private var nextId = 100

    @Volatile
    private var tree = build()

    @Volatile
    private var unlocked = true

    private val edits = object : DriveEdits {
        override suspend fun createFolder(parentId: Id?, name: String) {
            folders[id(nextId++)] = LocalFolder(name, parentId)
            tree = build()
        }

        override suspend fun move(entry: DriveTree.Entry, from: DriveTree.Folder, shownName: String, to: DriveTree.Folder, name: String) {
            when (entry) {
                is DriveTree.File -> files[entry.fileId] = files.getValue(entry.fileId).copy(name = name, folderId = to.folderId)
                is DriveTree.Folder -> folders[entry.folderId!!] = LocalFolder(name, to.folderId)
            }
            tree = build()
        }

        override suspend fun delete(entry: DriveTree.Entry) {
            when (entry) {
                is DriveTree.File -> files.remove(entry.fileId)
                is DriveTree.Folder -> folders.remove(entry.folderId)
            }
            tree = build()
        }
    }

    private lateinit var at: Path
    private lateinit var mounted: VaultDrive.Mounted

    private fun build() = DriveTree.of(VaultTree(folders.toMap(), files.toMap()), files.keys.associateWith { indexed(it, contents[it]?.size ?: 0) })

    @Before
    fun mount() {
        assumeTrue("no FUSE here", File("/dev/fuse").exists())
        at = Files.createTempDirectory(Path.of("build").toAbsolutePath().also(Files::createDirectories), "drive")
        mounted = VaultDrive.mount(
            at,
            tree = { tree },
            open = { file ->
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
            },
            edits = { if (unlocked) edits else null },
        )
    }

    @After
    fun unmount() {
        if (::mounted.isInitialized) {
            mounted.close()
            Files.deleteIfExists(at)
        }
    }

    private fun names(dir: Path = at) = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() }

    @Test
    fun foldersFilesAndSizesShowAsTheVaultHasThem() {
        assertEquals(setOf("Docs", "small.txt"), names())
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
    fun aFolderMadeIsThereAtOnceAndNoTrashAtTheTop() {
        Files.createDirectory(at.resolve("New folder"))
        assertTrue(Files.isDirectory(at.resolve("New folder")))
        Files.createDirectory(at.resolve("New folder/Inner"))
        assertEquals(setOf("Inner"), names(at.resolve("New folder")))
        try {
            Files.createDirectory(at.resolve(".Trash-1000"))
            fail("a trash folder was made at the top")
        } catch (_: AccessDeniedException) {
        }
        assertFalse(".Trash-1000" in names())
        assertFalse(folders.values.any { it.name.startsWith(".Trash") })
    }

    @Test
    fun filesAndFoldersRenameAndMove() {
        Files.move(at.resolve("small.txt"), at.resolve("renamed.txt"), StandardCopyOption.ATOMIC_MOVE)
        assertArrayEquals(small, Files.readAllBytes(at.resolve("renamed.txt")))
        Files.move(at.resolve("renamed.txt"), at.resolve("Docs/renamed.txt"), StandardCopyOption.ATOMIC_MOVE)
        assertEquals(setOf("big.bin", "renamed.txt"), names(at.resolve("Docs")))
        assertEquals(docsId, files.getValue(smallId).folderId)
        Files.move(at.resolve("Docs"), at.resolve("Papers"), StandardCopyOption.ATOMIC_MOVE)
        assertArrayEquals(big, Files.readAllBytes(at.resolve("Papers/big.bin")))
        assertEquals(setOf("Papers"), names())
    }

    @Test
    fun renamingOntoAFileReplacesIt() {
        Files.move(at.resolve("Docs/big.bin"), at.resolve("small.txt"), StandardCopyOption.ATOMIC_MOVE)
        assertArrayEquals(big, Files.readAllBytes(at.resolve("small.txt")))
        assertFalse(smallId in files)
    }

    @Test
    fun aFolderDoesNotGoIntoItself() {
        Files.createDirectory(at.resolve("Docs/Inner"))
        try {
            Files.move(at.resolve("Docs"), at.resolve("Docs/Inner/Docs"), StandardCopyOption.ATOMIC_MOVE)
            fail("moved into itself")
        } catch (_: FileSystemException) {
        }
        assertEquals(null, folders.getValue(docsId).parentId)
    }

    @Test
    fun deletingTakesFilesAndEmptyFoldersOnly() {
        try {
            Files.delete(at.resolve("Docs"))
            fail("a folder with something in it was deleted")
        } catch (_: DirectoryNotEmptyException) {
        }
        Files.delete(at.resolve("Docs/big.bin"))
        Files.delete(at.resolve("Docs"))
        Files.delete(at.resolve("small.txt"))
        assertEquals(emptySet<String>(), names())
        assertTrue(files.isEmpty() && folders.isEmpty())
    }

    @Test
    fun filesCannotBeMadeOrWrittenYet() {
        assertReadOnly { Files.write(at.resolve("new.txt"), small) }
        assertReadOnly { Files.newOutputStream(at.resolve("small.txt"), StandardOpenOption.APPEND).use { it.write(1) } }
        assertArrayEquals(small, Files.readAllBytes(at.resolve("small.txt")))
    }

    @Test
    fun aLockedVaultChangesNothing() {
        unlocked = false
        assertReadOnly { Files.createDirectory(at.resolve("New folder")) }
        assertReadOnly { Files.delete(at.resolve("small.txt")) }
        assertEquals(setOf("Docs", "small.txt"), names())
    }

    private fun assertReadOnly(write: () -> Unit) {
        try {
            write()
            fail("written")
        } catch (e: FileSystemException) {
            assertTrue(e.toString(), "Read-only file system" in e.reason.orEmpty())
        }
    }

    private fun id(n: Int) = Id.of(ByteArray(16) { if (it == 15) n.toByte() else 7 })

    private fun meta(name: String, folder: Id?) = FileMeta(
        name, folder, "application/octet-stream", 1_600_000_000_000, 1_700_000_000_000,
        Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES)), Bytes.of(ByteArray(CofferFormat.WRAPPED_KEY_BYTES)),
    )

    private fun indexed(id: Id, size: Int) = IndexedFile(id, size.toLong(), 1, ByteArray(0), FileStatus.READY, emptyMap())
}
