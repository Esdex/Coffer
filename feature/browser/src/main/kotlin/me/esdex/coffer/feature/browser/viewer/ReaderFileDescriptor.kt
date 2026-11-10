package me.esdex.coffer.feature.browser.viewer

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.sync.VaultFileReader
import java.io.Closeable

/**
 * A real, seekable descriptor for a file that exists only in Saved Messages, after Arcanum's
 * VaultFileDescriptor. ImageDecoder and PdfRenderer read nothing else, and a decrypted copy on disk
 * is what the chunked reader is there to avoid: every read of this descriptor arrives as a callback,
 * on a thread of its own, and goes to [reader] (pre-release item 5).
 */
class ReaderFileDescriptor(context: Context, private val reader: VaultFileReader) : Closeable {

    private val thread = HandlerThread("coffer-fd").apply { start() }

    val descriptor: ParcelFileDescriptor = runCatching {
        context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = reader.size

                // Blocking is what this thread is for: the caller of read() is waiting on it either way.
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                    runBlocking { reader.read(offset, data, 0, size) }.coerceAtLeast(0)
                } catch (e: Exception) {
                    throw ErrnoException("onRead", OsConstants.EIO).apply { initCause(e) }
                }

                override fun onRelease() {}
            },
            Handler(thread.looper),
        )
    }.getOrElse { failure ->
        thread.quitSafely()
        throw failure
    }

    /**
     * The FUSE loop behind the descriptor sends a last command or two after the close; quitting from
     * the thread itself puts the quit behind them, as Arcanum learned.
     */
    override fun close() {
        runCatching { descriptor.close() }
        if (!Handler(thread.looper).post { thread.quitSafely() }) thread.quitSafely()
    }
}
