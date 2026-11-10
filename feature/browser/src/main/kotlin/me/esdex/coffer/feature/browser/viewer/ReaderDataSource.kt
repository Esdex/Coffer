package me.esdex.coffer.feature.browser.viewer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.sync.VaultFileReader
import java.io.IOException

/**
 * What the player reads a video through, after Arcanum's EncryptedDataSource: every range it asks
 * for comes from [reader], a chunk at a time from Saved Messages, so a video starts before it is
 * downloaded and a seek fetches only where it lands. The player reads on a loader thread of its
 * own, which is why blocking here is fine.
 */
@OptIn(UnstableApi::class)
class ReaderDataSource(private val reader: VaultFileReader) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        if (dataSpec.position > reader.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else reader.size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = try {
            runBlocking { reader.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt()) }
        } catch (e: Exception) {
            throw IOException(e)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
