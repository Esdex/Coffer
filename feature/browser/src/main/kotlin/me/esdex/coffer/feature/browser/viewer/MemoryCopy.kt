package me.esdex.coffer.feature.browser.viewer

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.esdex.coffer.core.sync.VaultFileReader
import java.io.Closeable
import java.io.IOException

/**
 * A vault file decrypted whole into memory the kernel holds for this process alone (a memfd), for the
 * decoders that will read nothing but a real descriptor: ImageDecoder and PdfRenderer. Not on disk, not
 * in the Java heap, and not a ProxyFileDescriptor, which the viewers read through before: that one is
 * served by a thread of this same process, and a process killed while a decoder waits on it can never
 * exit, and Coffer will not start again until the phone restarts (seen 2026-11-11, see PreviewMaker).
 * Closing frees the memory.
 */
class MemoryCopy private constructor(val descriptor: ParcelFileDescriptor) : Closeable {

    override fun close() {
        runCatching { descriptor.close() }
    }

    /** Over [MAX_BYTES]: held in memory whole, it would be too much. */
    class TooLargeException : IOException("too large to hold in memory")

    companion object {
        /** Agreed with Esdex 2026-11-11 as a start; larger files open in another app. */
        const val MAX_BYTES = 256L * 1024 * 1024

        /** Copies all of [reader]; throws [TooLargeException], or the reader's failure. */
        suspend fun of(reader: VaultFileReader, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): MemoryCopy {
            if (reader.size > MAX_BYTES) throw TooLargeException()
            // The name shows in /proc as /memfd:coffer; never the file's own.
            val fd = Os.memfd_create("coffer", OsConstants.MFD_CLOEXEC)
            try {
                copyWhole(reader.size, reader::read, { bytes, length ->
                    var at = 0
                    while (at < length) at += Os.write(fd, bytes, at, length - at)
                }, onProgress)
                // Read from the start: the copy left the offset at the end, and a dup shares it.
                Os.lseek(fd, 0, OsConstants.SEEK_SET)
                return MemoryCopy(ParcelFileDescriptor.dup(fd))
            } finally {
                Os.close(fd)
            }
        }
    }
}

/**
 * Reads [size] bytes through [read] and hands them to [write] in order, a chunk at a time, reporting
 * progress. A file that ends early is an error, not a shorter copy: a decoder would take it as damage.
 * The buffer is wiped after, as it held plaintext.
 */
internal suspend fun copyWhole(
    size: Long,
    read: suspend (position: Long, into: ByteArray, offset: Int, length: Int) -> Int,
    write: (bytes: ByteArray, length: Int) -> Unit,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
) {
    val buffer = ByteArray(COPY_BUFFER)
    try {
        var done = 0L
        onProgress(done, size)
        while (done < size) {
            currentCoroutineContext().ensureActive()
            val n = read(done, buffer, 0, minOf(buffer.size.toLong(), size - done).toInt())
            if (n <= 0) throw IOException("the file ended at $done of $size bytes")
            write(buffer, n)
            done += n
            onProgress(done, size)
        }
    } finally {
        buffer.fill(0)
    }
}

/** A chunk of the format's: the reader decrypts and hands over no more than that at a time anyway. */
private const val COPY_BUFFER = 1 shl 20
