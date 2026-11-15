package me.esdex.coffer.core.crypto

import me.esdex.coffer.core.crypto.sodium.Sodium
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream

/**
 * A file kept on this phone only, encrypted as the vault's content is (SPEC §3.6): chunks of 1 MiB,
 * each `nonce || XChaCha20-Poly1305`, the last one marked as last in its AD, so a copy cut short or
 * spliced does not open. The key is random and the file's own; the caller keeps it sealed by the
 * phone's Keystore. For copies of shared files waiting on the phone to be uploaded (audit 2, N4).
 */
object LocalStream {
    fun newKey(sodium: Sodium): SecretKey = SecretKey.adopt(sodium.randomBytes(CofferFormat.KEY_BYTES))

    /** Encrypts what is written into [out]; [OutputStream.close] writes the last chunk. */
    fun sealing(sodium: Sodium, key: SecretKey, out: OutputStream): OutputStream = Sealing(ChunkCipher(sodium, key, STREAM_ID), out)

    /** Decrypts [input]; throws [IOException] for a stream that does not authenticate or ends early. */
    fun opening(sodium: Sodium, key: SecretKey, input: InputStream): InputStream = Opening(ChunkCipher(sodium, key, STREAM_ID), input)

    // The key is the file's own, never used for anything else: the AD needs no id of its own.
    private val STREAM_ID = Id.of(ByteArray(CofferFormat.ID_BYTES))

    private class Sealing(private val cipher: ChunkCipher, private val out: OutputStream) : OutputStream() {
        private val buffer = ByteArray(CofferFormat.CHUNK_SIZE)
        private var filled = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                // A full chunk is sent only once more follows: until then it may be the last.
                if (filled == buffer.size) flushChunk(isLast = false)
                val n = minOf(left, buffer.size - filled)
                b.copyInto(buffer, filled, at, at + n)
                filled += n
                at += n
                left -= n
            }
        }

        private fun flushChunk(isLast: Boolean) {
            out.write(cipher.encrypt(index++, isLast, buffer.copyOf(filled)))
            buffer.fill(0, 0, filled)
            filled = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushChunk(isLast = true)
            } finally {
                out.close()
            }
        }
    }

    private class Opening(private val cipher: ChunkCipher, input: InputStream) : InputStream() {
        private val input = PushbackInputStream(input, 1)
        private var chunk = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == chunk.size) {
                if (ended) return -1
                nextChunk()
            }
            val n = minOf(len, chunk.size - position)
            chunk.copyInto(b, off, position, position + n)
            position += n
            return n
        }

        private fun nextChunk() {
            val sealed = ByteArray(CofferFormat.ENCRYPTED_CHUNK_SIZE)
            var got = 0
            while (got < sealed.size) {
                val n = input.read(sealed, got, sealed.size - got)
                if (n < 0) break
                got += n
            }
            // A full chunk is the last one only if nothing follows it.
            val isLast = got < sealed.size || peekEnd()
            if (got < CofferFormat.ENCRYPTED_CHUNK_OVERHEAD) throw IOException("the file ends before its last chunk")
            chunk = try {
                cipher.decrypt(index++, isLast, sealed.copyOf(got))
            } catch (e: AuthenticationException) {
                throw IOException("the file does not open", e)
            }
            position = 0
            ended = isLast
        }

        private fun peekEnd(): Boolean {
            val next = input.read()
            if (next < 0) return true
            input.unread(next)
            return false
        }

        override fun close() = input.close()
    }
}
