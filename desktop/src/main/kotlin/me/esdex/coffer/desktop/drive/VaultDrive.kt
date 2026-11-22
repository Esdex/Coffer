package me.esdex.coffer.desktop.drive

import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.sync.VaultFileReader
import me.esdex.coffer.desktop.vault.log
import org.cryptomator.jfuse.api.DirFiller
import org.cryptomator.jfuse.api.Errno
import org.cryptomator.jfuse.api.FileInfo
import org.cryptomator.jfuse.api.Fuse
import org.cryptomator.jfuse.api.FuseOperations
import org.cryptomator.jfuse.api.FuseOperations.Operation
import org.cryptomator.jfuse.api.Stat
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A vault file open on the drive: [read] as [VaultFileReader.read] does, up to the length asked from a
 * position, -1 past the end. [close] wipes its key and the plain text held.
 */
class OpenFile(
    val read: suspend (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
    private val onClose: () -> Unit,
) : AutoCloseable {
    override fun close() = onClose()
}

/**
 * The unlocked vault as a drive, read only for now: [tree] says what is where, [open] reads a file in place
 * through [VaultFileReader], chunk by chunk as the reading app asks, nothing decrypted to disk. Only the user
 * who mounted it can reach it (FUSE's default without allow_other).
 */
class VaultDrive private constructor(
    private val errno: Errno,
    private val tree: () -> DriveTree,
    private val open: (DriveTree.File) -> OpenFile?,
) : FuseOperations {
    private val handles = ConcurrentHashMap<Long, OpenFile>()
    private val nextHandle = AtomicLong(1)

    // Owned by the user who mounted it, as the vault is theirs alone.
    private val uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int
    private val gid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:gid") as Int

    override fun errno(): Errno = errno

    override fun supportedOperations(): Set<Operation> =
        setOf(Operation.GET_ATTR, Operation.OPEN_DIR, Operation.READ_DIR, Operation.RELEASE_DIR, Operation.OPEN, Operation.READ, Operation.RELEASE)

    override fun getattr(path: String, stat: Stat, fi: FileInfo?): Int {
        val entry = tree()[path] ?: return -errno.enoent()
        stat.setUid(uid)
        stat.setGid(gid)
        when (entry) {
            is DriveTree.Folder -> {
                stat.setMode(Stat.S_IFDIR or "500".toInt(8))
                stat.setNLink(2)
            }
            is DriveTree.File -> {
                stat.setMode(Stat.S_IFREG or "400".toInt(8))
                stat.setNLink(1)
                stat.setSize(entry.size)
                stat.birthTime().set(Instant.ofEpochMilli(entry.createdAt))
            }
        }
        val modified = Instant.ofEpochMilli(entry.modifiedAt)
        stat.mTime().set(modified)
        stat.cTime().set(modified)
        stat.aTime().set(modified)
        return 0
    }

    override fun opendir(path: String, fi: FileInfo): Int = if (tree()[path] is DriveTree.Folder) 0 else -errno.enoent()

    override fun readdir(path: String, filler: DirFiller, offset: Long, fi: FileInfo, flags: Int): Int {
        val folder = tree()[path] as? DriveTree.Folder ?: return -errno.enoent()
        filler.fill(".")
        filler.fill("..")
        folder.children.forEach(filler::fill)
        return 0
    }

    override fun releasedir(path: String?, fi: FileInfo): Int = 0

    override fun open(path: String, fi: FileInfo): Int {
        val file = when (val entry = tree()[path]) {
            null -> return -errno.enoent()
            is DriveTree.Folder -> return -errno.eisdir()
            is DriveTree.File -> entry
        }
        if (fi.openFlags.any { it in WRITES }) return -errno.erofs()
        val opened = try {
            open(file)
        } catch (e: Exception) {
            log("opening a file failed (${e.javaClass.simpleName})")
            null
        } ?: return -errno.eio()
        val handle = nextHandle.getAndIncrement()
        handles[handle] = opened
        fi.setFh(handle)
        return 0
    }

    override fun read(path: String, buf: ByteBuffer, count: Long, offset: Long, fi: FileInfo): Int {
        val file = handles[fi.fh] ?: return -errno.ebadf()
        val bytes = ByteArray(count.toInt())
        return try {
            val n = runBlocking { file.read(offset, bytes, 0, bytes.size) }
            if (n <= 0) return 0
            buf.put(bytes, 0, n)
            n
        } catch (e: Exception) {
            // Its kind only: a message could name what is in the vault.
            log("reading a file failed (${e.javaClass.simpleName})")
            -errno.eio()
        } finally {
            bytes.fill(0)
        }
    }

    override fun release(path: String, fi: FileInfo): Int {
        handles.remove(fi.fh)?.close()
        return 0
    }

    /** Lets go of every file still open: the vault is locking, and their keys go with it. */
    private fun closeAll() {
        handles.keys.toList().forEach { handles.remove(it)?.close() }
    }

    /** The drive, mounted at [at] until [close], which also closes what is still open on it. */
    class Mounted internal constructor(private val fuse: Fuse, private val drive: VaultDrive, val at: Path) : AutoCloseable {
        override fun close() {
            drive.closeAll()
            fuse.close()
        }
    }

    companion object {
        private val WRITES = setOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.CREATE_NEW)

        /** Mounts it at [at], made if missing; blocks until the system sees it. */
        fun mount(at: Path, tree: () -> DriveTree, open: (DriveTree.File) -> OpenFile?): Mounted {
            Files.createDirectories(at)
            val builder = Fuse.builder()
            val drive = VaultDrive(builder.errno(), tree, open)
            val fuse = builder.build(drive)
            // Read only at the kernel too: writes fail as "Read-only file system", and file managers grey them out,
            // where an operation left out would answer "Function not implemented".
            fuse.mount("coffer", at, "-ofsname=coffer", "-osubtype=coffer", "-oro")
            return Mounted(fuse, drive, at)
        }
    }
}
