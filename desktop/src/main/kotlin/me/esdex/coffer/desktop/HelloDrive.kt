package me.esdex.coffer.desktop

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

/**
 * A first drive, to try the mount on its own: one folder with one read-only file. No vault behind it
 * yet; it shows that libfuse, jfuse and the desktop's file manager get along before the vault is put
 * behind the same calls.
 */
class HelloDrive private constructor(private val errno: Errno) : FuseOperations {
    override fun errno(): Errno = errno

    override fun supportedOperations(): Set<Operation> =
        setOf(Operation.GET_ATTR, Operation.OPEN_DIR, Operation.READ_DIR, Operation.RELEASE_DIR, Operation.OPEN, Operation.READ, Operation.RELEASE)

    override fun getattr(path: String, stat: Stat, fi: FileInfo?): Int {
        when (path) {
            "/" -> {
                stat.setMode(Stat.S_IFDIR or "555".toInt(8))
                stat.setNLink(2)
            }
            "/$NAME" -> {
                stat.setMode(Stat.S_IFREG or "444".toInt(8))
                stat.setNLink(1)
                stat.setSize(TEXT.size.toLong())
            }
            else -> return -errno.enoent()
        }
        return 0
    }

    override fun opendir(path: String, fi: FileInfo): Int = if (path == "/") 0 else -errno.enoent()

    override fun readdir(path: String, filler: DirFiller, offset: Long, fi: FileInfo, flags: Int): Int {
        if (path != "/") return -errno.enoent()
        filler.fill(".")
        filler.fill("..")
        filler.fill(NAME)
        return 0
    }

    override fun releasedir(path: String?, fi: FileInfo): Int = 0

    override fun open(path: String, fi: FileInfo): Int = if (path == "/$NAME") 0 else -errno.enoent()

    override fun read(path: String, buf: ByteBuffer, count: Long, offset: Long, fi: FileInfo): Int {
        if (path != "/$NAME") return -errno.enoent()
        if (offset >= TEXT.size) return 0
        val n = minOf(count, TEXT.size - offset).toInt()
        buf.put(TEXT, offset.toInt(), n)
        return n
    }

    override fun release(path: String, fi: FileInfo): Int = 0

    /** The drive, mounted at [at] until [close]. */
    class Mounted(private val fuse: Fuse, val at: Path) : AutoCloseable {
        override fun close() = fuse.close()
    }

    companion object {
        private const val NAME = "hello.txt"
        private val TEXT = "Coffer's drive is mounted. The vault comes next.\n".toByteArray()

        /** Mounts it at [at], made if missing; blocks until the system sees it. */
        fun mount(at: Path): Mounted {
            Files.createDirectories(at)
            val builder = Fuse.builder()
            val fuse = builder.build(HelloDrive(builder.errno()))
            fuse.mount("coffer", at, "-ofsname=coffer", "-osubtype=coffer")
            return Mounted(fuse, at)
        }
    }
}
