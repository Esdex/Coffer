package me.esdex.coffer.feature.browser.viewer.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import me.esdex.coffer.core.sync.VaultFileReader
import me.esdex.coffer.feature.browser.viewer.ReaderMediaSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Works a track's waveform out from its whole audio, in order, after Arcanum's decodeWaveform: every frame
 * goes to a [WaveformBuilder], so a bar is the loudness of all of its stretch. As fast as the track comes
 * in, on its own reader, whatever the player is doing. Blocking calls run here: call it off the main thread.
 */
internal object WaveformDecoder {

    /** How often the waveform is shown as far as it has got: four times a second. */
    private const val PARTIAL_EVERY_NS = 250_000_000L

    /** The bars, or null if the track could not be read or decoded; cancelling stops it. */
    suspend fun decode(reader: VaultFileReader, onPartial: (List<Float>) -> Unit): List<Float>? {
        val failed = AtomicBoolean(false)
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(ReaderMediaSource(reader, failed))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            // Without a duration the waveform is shown only once it is done.
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var lastPartial = System.nanoTime()

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            // 50 ms blocks: fine enough for any number of bars, few enough for an hour-long mix.
            val builder = WaveformBuilder((format.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 20).coerceAtLeast(1))
            var pcm = PcmReader(AudioFormat.ENCODING_PCM_16BIT, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (currentCoroutineContext().isActive) {
                var moved = false
                // Every free input buffer filled and every ready output taken before waiting, so the
                // decoder, in another process, is never left idle between frames.
                while (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    val size = extractor.readSampleData(codec.getInputBuffer(inIdx)!!, 0)
                    if (failed.get()) return null
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    moved = true
                }
                while (true) {
                    // Wait only when nothing at all moved; otherwise take what is ready and go on feeding.
                    val outIdx = codec.dequeueOutputBuffer(info, if (moved) 0L else 5_000L)
                    when {
                        outIdx >= 0 -> {
                            moved = true
                            if (info.size > 0) pcm.read(codec.getOutputBuffer(outIdx)!!, info, builder)
                            codec.releaseOutputBuffer(outIdx, false)
                            if (durationUs > 0 && System.nanoTime() - lastPartial >= PARTIAL_EVERY_NS) {
                                lastPartial = System.nanoTime()
                                onPartial(builder.partialBars(WaveformBytes.BARS, info.presentationTimeUs.toDouble() / durationUs))
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return builder.bars(WaveformBytes.BARS)
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            pcm = PcmReader(
                                out.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT),
                                out.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2),
                            )
                        }
                        else -> break
                    }
                }
                // A decoder that stops answering would otherwise hold this job forever (about 5 s).
                idle = if (moved) 0 else idle + 1
                if (idle > 1000) return null
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }
}

/** Decoded PCM of any encoding into frames' energy, after Arcanum's PcmReader. */
private class PcmReader(private val encoding: Int, private val channels: Int) {

    // Reused between buffers: a buffer is copied out in one go and walked as an array, which costs a
    // fraction of fetching every sample from the ByteBuffer on its own.
    private var shorts = ShortArray(0)
    private var floats = FloatArray(0)
    private var ints = IntArray(0)
    private var bytes = ByteArray(0)

    fun read(buffer: ByteBuffer, info: MediaCodec.BufferInfo, into: WaveformBuilder) {
        if (channels <= 0) return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val b = buffer.slice().order(ByteOrder.nativeOrder())
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val n = b.remaining() / 2
                if (shorts.size < n) shorts = ShortArray(n)
                b.asShortBuffer().get(shorts, 0, n)
                frames(n, into) { shorts[it] / 32768.0 }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val n = b.remaining() / 4
                if (floats.size < n) floats = FloatArray(n)
                b.asFloatBuffer().get(floats, 0, n)
                frames(n, into) { floats[it].toDouble() }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val n = b.remaining() / 4
                if (ints.size < n) ints = IntArray(n)
                b.asIntBuffer().get(ints, 0, n)
                frames(n, into) { ints[it] / 2147483648.0 }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val n = b.remaining() / 3
                if (bytes.size < n * 3) bytes = ByteArray(n * 3)
                b.get(bytes, 0, n * 3)
                frames(n, into) {
                    // Little-endian, as nativeOrder is on every Android device; the top byte keeps its sign.
                    val lo = bytes[it * 3].toInt() and 0xFF
                    val mid = bytes[it * 3 + 1].toInt() and 0xFF
                    val hi = bytes[it * 3 + 2].toInt()
                    ((hi shl 16) or (mid shl 8) or lo) / 8388608.0
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val n = b.remaining()
                if (bytes.size < n) bytes = ByteArray(n)
                b.get(bytes, 0, n)
                frames(n, into) { ((bytes[it].toInt() and 0xFF) - 128) / 128.0 }
            }
        }
    }

    /** Walks [samples] interleaved samples, [sample] giving the one at an index in -1..1. */
    private inline fun frames(samples: Int, into: WaveformBuilder, sample: (Int) -> Double) {
        var i = 0
        while (i + channels <= samples) {
            var sum = 0.0
            for (c in 0 until channels) {
                val s = sample(i + c)
                sum += s * s
            }
            into.addFrame(sum / channels)
            i += channels
        }
    }
}
