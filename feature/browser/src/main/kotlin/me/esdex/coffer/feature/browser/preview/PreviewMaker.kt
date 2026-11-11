package me.esdex.coffer.feature.browser.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.sync.VaultFileReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Makes a file's preview from as little of it as will do: a photo's own small copy from its EXIF, else
 * the whole picture if it is not too big; a frame near the start of a video; a song's cover from its
 * tags. Square, [SIZE] pixels a side, WebP. Blocking: call it off the main thread.
 *
 * Everything is read through streams and MediaDataSource, never through a ProxyFileDescriptor: that
 * one is served by a thread of this same process, and a process killed with a decoder waiting on it can
 * never exit; Android then will not start Coffer again until the phone restarts (seen 2026-11-11).
 * Several ideas here are Arcanum's ThumbnailManager: decoding a photo from a stream in two passes, HEIF
 * read whole, and the video frame chosen by its detail.
 */
internal object PreviewMaker {
    /** The tile is 42 dp: about 126 px on a 3x screen. */
    const val SIZE = 144

    /**
     * A picture with no small copy of its own is decoded whole, which means downloading it whole: up to
     * this size only, as a preview is not worth more traffic than that.
     */
    private const val WHOLE_PICTURE_MAX_BYTES = 32L * 1024 * 1024

    private const val QUALITY = 80

    /** A frame whose luma varies less than this is a solid fill: a black lead-in, a white title card. */
    private const val SOLID_FRAME = 6.0

    /** Null if reading the file failed (try again later); empty bytes if it has no preview to give. */
    fun make(reader: VaultFileReader, mime: String, kind: PreviewKind): ByteArray? {
        val failed = AtomicBoolean(false)
        val bitmap = try {
            when (kind) {
                PreviewKind.PICTURE -> picture(reader, mime, failed)
                PreviewKind.VIDEO -> retrieve(reader, failed, ::frame)
                PreviewKind.AUDIO -> retrieve(reader, failed) { it.embeddedPicture?.let(::decodeSampled) }
            }
        } catch (_: Exception) {
            // Damaged or of a kind Android does not read: no preview, unless the reading itself failed.
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        if (failed.get()) return null
        return bitmap?.let(::encode) ?: ByteArray(0)
    }

    private fun picture(reader: VaultFileReader, mime: String, failed: AtomicBoolean): Bitmap? {
        // HEIF wants its size up front and refuses a stream; ImageDecoder turns it upright itself.
        if (mime == "image/heic" || mime == "image/heif") {
            val whole = readWhole(reader, failed) ?: return null
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(whole))) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        // A JPEG's EXIF comes before the picture, and it stops reading at the picture.
        val exif = runCatching { reader.stream(failed).use(::ExifInterface) }.getOrNull()
        if (failed.get()) return null
        exif?.thumbnailBitmap?.let { return rotated(it, exif.rotationDegrees) }
        if (reader.size > WHOLE_PICTURE_MAX_BYTES) return null
        return reader.stream(failed).use { stream ->
            // Bounds first, then the picture at a fraction of its size: a 50-megapixel photo never whole in memory.
            stream.mark(0)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, bounds)
            if (bounds.outWidth <= 0) return@use null
            stream.reset()
            val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
            val bitmap = BitmapFactory.decodeStream(stream, null, options) ?: return@use null
            // A decoder that turned it upright already gives a portrait from a landscape: not twice.
            val degrees = exif?.rotationDegrees ?: 0
            val turnedAlready = degrees % 180 != 0 && bounds.outWidth > bounds.outHeight && bitmap.height > bitmap.width
            if (turnedAlready) bitmap else rotated(bitmap, degrees)
        }
    }

    /**
     * The cover the muxer stored, if any; else the first frame, unless it is a solid fill, when one a
     * little later is tried and the one with more to see is kept.
     */
    private fun frame(retriever: MediaMetadataRetriever): Bitmap? {
        retriever.embeddedPicture?.let(::decodeSampled)?.let { return it }
        val first = retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE * 2, SIZE * 2) ?: return null
        val firstDetail = detail(first)
        if (firstDetail >= SOLID_FRAME) return first
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return first
        // 30% in clears a fade-in; within 5 s, so a long video is not read far into for it.
        val atMs = minOf(durationMs * 30 / 100, 5_000L)
        if (atMs <= 0) return first
        val later = retriever.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE * 2, SIZE * 2) ?: return first
        return if (detail(later) > firstDetail) later else first
    }

    /** Spread of luma over an 8x8 reduction: about 0 for a solid fill, well above it for a scene. */
    private fun detail(frame: Bitmap): Double = try {
        val small = frame.scale(8, 8)
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        val luma = pixels.map { 0.299 * ((it shr 16) and 0xFF) + 0.587 * ((it shr 8) and 0xFF) + 0.114 * (it and 0xFF) }
        val mean = luma.average()
        sqrt(luma.sumOf { (it - mean) * (it - mean) } / luma.size)
    } catch (_: Exception) {
        // A frame that cannot be looked at closely is kept as it is.
        Double.MAX_VALUE
    }

    private fun retrieve(reader: VaultFileReader, failed: AtomicBoolean, take: (MediaMetadataRetriever) -> Bitmap?): Bitmap? =
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(ReaderMediaSource(reader, failed))
            if (failed.get()) null else take(retriever)
        }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** The whole file, if it is within [WHOLE_PICTURE_MAX_BYTES]; null otherwise or if a read failed. */
    private fun readWhole(reader: VaultFileReader, failed: AtomicBoolean): ByteArray? {
        if (reader.size <= 0 || reader.size > WHOLE_PICTURE_MAX_BYTES) return null
        val out = ByteArray(reader.size.toInt())
        reader.stream(failed).use { stream ->
            var at = 0
            while (at < out.size) {
                val n = stream.read(out, at, out.size - at)
                if (n <= 0) return null
                at += n
            }
        }
        return out
    }

    /** Halves while the short side stays at least twice [SIZE]: decoding a 48-megapixel photo whole is not needed. */
    private fun sampleFor(width: Int, height: Int): Int {
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= SIZE * 2) sample *= 2
        return sample
    }

    private fun rotated(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** The middle square, scaled down to [SIZE]: the tile crops the same way. */
    private fun encode(bitmap: Bitmap): ByteArray {
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = if (side > SIZE) square.scale(SIZE, SIZE) else square
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
            out.toByteArray()
        }
    }
}

private fun VaultFileReader.stream(failed: AtomicBoolean) = ReaderInputStream(size, failed) { at, into, offset, length -> read(at, into, offset, length) }

/** What MediaMetadataRetriever reads a vault file through: any offset, on demand, as the video player does. */
private class ReaderMediaSource(private val reader: VaultFileReader, private val failed: AtomicBoolean) : MediaDataSource() {
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
