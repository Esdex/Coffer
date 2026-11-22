package me.esdex.coffer.desktop.drive

import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.sync.VaultFileReader
import me.esdex.coffer.desktop.vault.log
import org.cryptomator.jfuse.api.DirFiller
import org.cryptomator.jfuse.api.Errno
import org.cryptomator.jfuse.api.FileInfo
import org.cryptomator.jfuse.api.Fuse
import org.cryptomator.jfuse.api.FuseOperations
import org.cryptomator.jfuse.api.FuseOperations.Operation
import org.cryptomator.jfuse.api.Stat
import org.cryptomator.jfuse.api.Statvfs
import org.cryptomator.jfuse.api.TimeSpec
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A file open on the drive: [read] as [VaultFileReader.read] does, up to the length asked from a position,
 * -1 past the end. A draft open for writing has [write], [truncate] and [sync] too. [close] wipes a vault
 * file's key and the plain text held, or lets a draft go up once it is quiet.
 */
class OpenFile(
    val read: suspend (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
    val write: ((position: Long, buffer: ByteArray, length: Int) -> Int)? = null,
    val truncate: ((size: Long) -> Unit)? = null,
    val sync: (() -> Unit)? = null,
    private val onClose: () -> Unit,
) : AutoCloseable {
    override fun close() = onClose()
}

/**
 * The unlocked vault as a drive: [tree] says what is where, [open] reads a file in place through
 * [VaultFileReader], chunk by chunk as the reading app asks, nothing decrypted to disk. Through [edits]
 * folders are made, things renamed, moved and deleted, and files written: as drafts, copies on this computer
 * that go up once closed and quiet. Only the user who mounted it can reach it (FUSE's default without
 * allow_other).
 *
 * No trash on the drive (Esdex, 2026-11-22): a trash folder cannot be made at its top, so a file manager
 * offers to delete for good, rather than copy a decrypted file into the home folder's trash.
 */
class VaultDrive private constructor(
    private val errno: Errno,
    private val tree: () -> DriveTree,
    private val open: (DriveTree.File) -> OpenFile?,
    private val edits: () -> DriveEdits?,
    private val space: Path,
) : FuseOperations {
    private val handles = ConcurrentHashMap<Long, OpenFile>()
    private val nextHandle = AtomicLong(1)

    // Owned by the user who mounted it, as the vault is theirs alone.
    private val uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int
    private val gid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:gid") as Int

    override fun errno(): Errno = errno

    override fun supportedOperations(): Set<Operation> = setOf(
        Operation.GET_ATTR, Operation.OPEN_DIR, Operation.READ_DIR, Operation.RELEASE_DIR, Operation.OPEN, Operation.READ, Operation.RELEASE,
        Operation.MKDIR, Operation.RENAME, Operation.UNLINK, Operation.RMDIR, Operation.CREATE, Operation.WRITE, Operation.TRUNCATE,
        Operation.FLUSH, Operation.FSYNC, Operation.UTIMENS, Operation.CHMOD, Operation.CHOWN, Operation.STATFS,
    )

    override fun getattr(path: String, stat: Stat, fi: FileInfo?): Int {
        val entry = tree()[path] ?: return -errno.enoent()
        stat.setUid(uid)
        stat.setGid(gid)
        when (entry) {
            is DriveTree.Folder -> {
                stat.setMode(Stat.S_IFDIR or "700".toInt(8))
                stat.setNLink(2)
            }
            is DriveTree.File -> {
                stat.setMode(Stat.S_IFREG or "600".toInt(8))
                stat.setNLink(1)
                stat.setSize(entry.size)
                stat.birthTime().set(Instant.ofEpochMilli(entry.createdAt))
            }
            is DriveTree.Draft -> {
                stat.setMode(Stat.S_IFREG or "600".toInt(8))
                stat.setNLink(1)
                val copy = edits()?.copyOf(entry)
                stat.setSize(copy?.length() ?: entry.size)
                val modified = Instant.ofEpochMilli(copy?.lastModified()?.takeIf { it > 0 } ?: entry.modifiedAt)
                stat.mTime().set(modified)
                stat.cTime().set(modified)
                stat.aTime().set(modified)
                return 0
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
        val flags = fi.openFlags
        val write = flags.any { it in WRITES }
        val truncate = StandardOpenOption.TRUNCATE_EXISTING in flags
        return when (val entry = tree()[path]) {
            null -> -errno.enoent()
            is DriveTree.Folder -> -errno.eisdir()
            is DriveTree.File -> if (write) opened(fi) { editFile(entry, truncate) } else read(fi) { open(entry) }
            is DriveTree.Draft -> opened(fi) { openDraft(entry, write, truncate) }
        }
    }

    override fun create(path: String, mode: Int, fi: FileInfo): Int {
        if (tree()[path] != null) {
            return if (StandardOpenOption.CREATE_NEW in fi.openFlags) -errno.eexist() else open(path, fi)
        }
        val (parentPath, name) = split(path) ?: return -errno.eexist()
        val parent = tree()[parentPath] as? DriveTree.Folder ?: return -errno.enoent()
        nameError(name)?.let { return it }
        return opened(fi) { createFile(parent.folderId, name) }
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

    override fun write(path: String, buf: ByteBuffer, count: Long, offset: Long, fi: FileInfo): Int {
        val write = handles[fi.fh]?.write ?: return -errno.ebadf()
        val bytes = ByteArray(count.toInt())
        buf.get(bytes)
        return try {
            write(offset, bytes, bytes.size)
        } catch (e: Exception) {
            log("writing a file failed (${e.javaClass.simpleName})")
            -errno.eio()
        } finally {
            bytes.fill(0)
        }
    }

    override fun truncate(path: String, size: Long, fi: FileInfo?): Int {
        fi?.let { handles[it.fh]?.truncate }?.let { truncate ->
            return try {
                truncate(size)
                0
            } catch (e: Exception) {
                log("truncating a file failed (${e.javaClass.simpleName})")
                -errno.eio()
            }
        }
        return when (val entry = tree()[path]) {
            null -> -errno.enoent()
            is DriveTree.Folder -> -errno.eisdir()
            is DriveTree.Draft -> change { resize(entry, size) }
            is DriveTree.File -> if (size == entry.size) 0 else change {
                editFile(entry, truncate = size == 0L).use { it.truncate!!(size) }
            }
        }
    }

    override fun flush(path: String, fi: FileInfo): Int = sync(fi)

    override fun fsync(path: String, datasync: Int, fi: FileInfo): Int = sync(fi)

    private fun sync(fi: FileInfo): Int = try {
        handles[fi.fh]?.sync?.invoke()
        0
    } catch (e: Exception) {
        log("syncing a file failed (${e.javaClass.simpleName})")
        -errno.eio()
    }

    // Times, modes and owners the vault does not keep: accepted and let be, as on a FAT stick, so a copy that
    // sets them afterwards does not fail.
    override fun utimens(path: String, mtime: TimeSpec, atime: TimeSpec, fi: FileInfo?): Int = if (tree()[path] != null) 0 else -errno.enoent()

    override fun chmod(path: String, mode: Int, fi: FileInfo?): Int = if (tree()[path] != null) 0 else -errno.enoent()

    override fun chown(path: String, uid: Int, gid: Int, fi: FileInfo?): Int = if (tree()[path] != null) 0 else -errno.enoent()

    /** The room of the disk the drafts are written to: a file manager checks it before it copies. */
    override fun statfs(path: String, statvfs: Statvfs): Int {
        val store = Files.getFileStore(space)
        statvfs.setBsize(BLOCK)
        statvfs.setFrsize(BLOCK)
        statvfs.setBlocks(store.totalSpace / BLOCK)
        statvfs.setBfree(store.unallocatedSpace / BLOCK)
        statvfs.setBavail(store.usableSpace / BLOCK)
        statvfs.setNameMax(CofferFormat.MAX_NAME_BYTES.toLong())
        return 0
    }

    override fun release(path: String, fi: FileInfo): Int {
        handles.remove(fi.fh)?.close()
        return 0
    }

    override fun mkdir(path: String, mode: Int): Int {
        val (parentPath, name) = split(path) ?: return -errno.eexist()
        val parent = tree()[parentPath] as? DriveTree.Folder ?: return -errno.enoent()
        if (tree()[path] != null) return -errno.eexist()
        nameError(name)?.let { return it }
        if (parent.folderId == null && (name == ".Trash" || name.startsWith(".Trash-"))) return -errno.eacces()
        return change { createFolder(parent.folderId, name) }
    }

    override fun unlink(path: String): Int = when (val entry = tree()[path]) {
        null -> -errno.enoent()
        is DriveTree.Folder -> -errno.eisdir()
        is DriveTree.File, is DriveTree.Draft -> change { delete(entry) }
    }

    override fun rmdir(path: String): Int = when (val entry = tree()[path]) {
        null -> -errno.enoent()
        is DriveTree.File, is DriveTree.Draft -> -errno.enotdir()
        is DriveTree.Folder -> when {
            entry.folderId == null -> -errno.eacces()
            entry.children.isNotEmpty() -> -errno.enotempty()
            else -> change { delete(entry) }
        }
    }

    override fun rename(oldpath: String, newpath: String, flags: Int): Int {
        val current = tree()
        val (fromPath, shownName) = split(oldpath) ?: return -errno.eacces()
        val (toPath, name) = split(newpath) ?: return -errno.eacces()
        val entry = current[oldpath] ?: return -errno.enoent()
        val from = current[fromPath] as? DriveTree.Folder ?: return -errno.enoent()
        val to = current[toPath] as? DriveTree.Folder ?: return -errno.enoent()
        if (flags and RENAME_EXCHANGE != 0) return -errno.einval()
        nameError(name)?.let { return it }
        val replaced = current[newpath]
        if (replaced != null) {
            if (replaced === entry) return 0
            if (flags and RENAME_NOREPLACE != 0) return -errno.eexist()
            when {
                entry !is DriveTree.Folder && replaced is DriveTree.Folder -> return -errno.eisdir()
                entry is DriveTree.Folder && replaced !is DriveTree.Folder -> return -errno.enotdir()
                replaced is DriveTree.Folder && replaced.children.isNotEmpty() -> return -errno.enotempty()
            }
        }
        return change { move(entry, from, shownName, to, name, replaced) }
    }

    /** A vault file opened for reading: [open] needs no edits, a locked vault has none anyway. */
    private fun read(fi: FileInfo, open: () -> OpenFile?): Int {
        val opened = try {
            open()
        } catch (e: Exception) {
            log("opening a file failed (${e.javaClass.simpleName})")
            null
        } ?: return -errno.eio()
        fi.setFh(keep(opened))
        return 0
    }

    /** A file opened through [edits]: for writing, or a draft. */
    private fun opened(fi: FileInfo, open: suspend DriveEdits.() -> OpenFile): Int {
        val target = edits() ?: return -errno.erofs()
        val opened = try {
            runBlocking { target.open() }
        } catch (e: Exception) {
            log("opening a file failed (${e.javaClass.simpleName})")
            return -errno.eio()
        }
        fi.setFh(keep(opened))
        return 0
    }

    private fun keep(opened: OpenFile): Long = nextHandle.getAndIncrement().also { handles[it] = opened }

    /** Runs [edit] on the open vault; a locked one is read only. */
    private fun change(edit: suspend DriveEdits.() -> Unit): Int {
        val target = edits() ?: return -errno.erofs()
        return try {
            runBlocking { target.edit() }
            0
        } catch (e: Exception) {
            // Its kind only: a message could name what is in the vault.
            log("a change on the drive failed (${e.javaClass.simpleName})")
            -errno.eio()
        }
    }

    /** Why [name] cannot be a vault name, as an error; null if it can. */
    private fun nameError(name: String): Int? = when {
        FileNames.utf8Size(name) > CofferFormat.MAX_NAME_BYTES -> -errno.enametoolong()
        !FileNames.isValid(name) -> -errno.einval()
        else -> null
    }

    /** "/a/b" as "/a" and "b"; null for the root. */
    private fun split(path: String): Pair<String, String>? {
        if (path == "/") return null
        val slash = path.lastIndexOf('/')
        return (if (slash == 0) "/" else path.substring(0, slash)) to path.substring(slash + 1)
    }

    /** Lets go of every file still open: the vault is locking, and their keys go with it. */
    private fun closeAll() {
        // Straight over the map: a file released on FUSE's thread meanwhile made toList() throw.
        for (handle in handles.keys) handles.remove(handle)?.close()
    }

    /** The drive, mounted at [at] until [close], which also closes what is still open on it. */
    class Mounted internal constructor(private val fuse: Fuse, private val drive: VaultDrive, val at: Path) : AutoCloseable {
        override fun close() {
            // Unmounted whatever closing the files did: a lock must not leave the drive there.
            try {
                drive.closeAll()
            } finally {
                fuse.close()
            }
        }
    }

    companion object {
        private val WRITES = setOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.CREATE_NEW)
        private const val BLOCK = 4096L

        // renameat2's flags.
        private const val RENAME_NOREPLACE = 1
        private const val RENAME_EXCHANGE = 2

        /** Mounts it at [at], made if missing; blocks until the system sees it. [space]: where drafts are written. */
        fun mount(at: Path, tree: () -> DriveTree, open: (DriveTree.File) -> OpenFile?, edits: () -> DriveEdits?, space: Path): Mounted {
            Files.createDirectories(at)
            val builder = Fuse.builder()
            val drive = VaultDrive(builder.errno(), tree, open, edits, space)
            val fuse = builder.build(drive)
            fuse.mount("coffer", at, "-ofsname=coffer", "-osubtype=coffer")
            return Mounted(fuse, drive, at)
        }
    }
}
