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
 * The unlocked vault as a drive: [tree] says what is where, [open] reads a file in place through
 * [VaultFileReader], chunk by chunk as the reading app asks, nothing decrypted to disk; folders are made,
 * things renamed, moved and deleted through [edits]. Writing files comes later: making one is refused as on
 * a read-only drive. Only the user who mounted it can reach it (FUSE's default without allow_other).
 *
 * No trash on the drive (Esdex, 2026-11-22): a trash folder cannot be made at its top, so a file manager
 * offers to delete for good, rather than copy a decrypted file into the home folder's trash.
 */
class VaultDrive private constructor(
    private val errno: Errno,
    private val tree: () -> DriveTree,
    private val open: (DriveTree.File) -> OpenFile?,
    private val edits: () -> DriveEdits?,
) : FuseOperations {
    private val handles = ConcurrentHashMap<Long, OpenFile>()
    private val nextHandle = AtomicLong(1)

    // Owned by the user who mounted it, as the vault is theirs alone.
    private val uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Int
    private val gid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:gid") as Int

    override fun errno(): Errno = errno

    override fun supportedOperations(): Set<Operation> = setOf(
        Operation.GET_ATTR, Operation.OPEN_DIR, Operation.READ_DIR, Operation.RELEASE_DIR, Operation.OPEN, Operation.READ, Operation.RELEASE,
        Operation.MKDIR, Operation.RENAME, Operation.UNLINK, Operation.RMDIR, Operation.CREATE,
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

    override fun create(path: String, mode: Int, fi: FileInfo): Int = -errno.erofs()

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
        is DriveTree.File -> change { delete(entry) }
    }

    override fun rmdir(path: String): Int = when (val entry = tree()[path]) {
        null -> -errno.enoent()
        is DriveTree.File -> -errno.enotdir()
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
                entry is DriveTree.File && replaced is DriveTree.Folder -> return -errno.eisdir()
                entry is DriveTree.Folder && replaced is DriveTree.File -> return -errno.enotdir()
                replaced is DriveTree.Folder && replaced.children.isNotEmpty() -> return -errno.enotempty()
            }
        }
        return change {
            // What is there goes, as rename(2) asks: one path, one thing.
            replaced?.let { delete(it) }
            move(entry, from, shownName, to, name)
        }
    }

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

        // renameat2's flags.
        private const val RENAME_NOREPLACE = 1
        private const val RENAME_EXCHANGE = 2

        /** Mounts it at [at], made if missing; blocks until the system sees it. */
        fun mount(at: Path, tree: () -> DriveTree, open: (DriveTree.File) -> OpenFile?, edits: () -> DriveEdits?): Mounted {
            Files.createDirectories(at)
            val builder = Fuse.builder()
            val drive = VaultDrive(builder.errno(), tree, open, edits)
            val fuse = builder.build(drive)
            fuse.mount("coffer", at, "-ofsname=coffer", "-osubtype=coffer")
            return Mounted(fuse, drive, at)
        }
    }
}
