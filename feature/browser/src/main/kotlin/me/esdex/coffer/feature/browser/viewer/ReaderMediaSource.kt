package me.esdex.coffer.feature.browser.viewer

import android.media.MediaDataSource
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.sync.VaultFileReader
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What MediaMetadataRetriever and MediaExtractor read a vault file through: any offset, on demand, as
 * the video player does. Not a file descriptor this process would serve itself (see MemoryCopy). A
 * failed read is noted in [failed]: the platform only says the source broke, not why.
 */
internal class ReaderMediaSource(private val reader: VaultFileReader, private val failed: AtomicBoolean) : MediaDataSource() {
    override fun getSize(): Long = reader.size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= reader.size) return -1
        return try {
            runBlocking { reader.read(position, buffer, offset, size) }
        } catch (e: Exception) {
            failed.set(true)
            throw IOException(e)
        }
    }

    override fun close() {}
}
