package me.esdex.coffer.core.crypto

import me.esdex.coffer.core.crypto.sodium.Sodium
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Where chunks and parts of a file of a given size live, SPEC §3.4, §3.5. Pure arithmetic. */
object ContentLayout {

    /** At least one chunk: an empty file is a single empty chunk. */
    fun chunkCount(plainSize: Long): Long {
        require(plainSize in 0..CofferFormat.MAX_PLAIN_SIZE) { "size out of range" }
        return maxOf(1L, ceilDiv(plainSize, CofferFormat.CHUNK_SIZE.toLong()))
    }

    fun partCount(plainSize: Long): Int = ceilDiv(chunkCount(plainSize), CofferFormat.CHUNKS_PER_PART.toLong()).toInt()

    fun chunkPlainSize(plainSize: Long, chunkIndex: Long): Int {
        val count = chunkCount(plainSize)
        require(chunkIndex in 0 until count) { "chunk index out of range" }
        return if (chunkIndex < count - 1) {
            CofferFormat.CHUNK_SIZE
        } else {
            (plainSize - chunkIndex * CofferFormat.CHUNK_SIZE).toInt()
        }
    }

    fun partOfChunk(chunkIndex: Long): Int = (chunkIndex / CofferFormat.CHUNKS_PER_PART).toInt()

    /** Byte offset of a chunk inside its part's document; every chunk before it in the part is full. */
    fun offsetInPart(chunkIndex: Long): Long =
        (chunkIndex % CofferFormat.CHUNKS_PER_PART) * CofferFormat.ENCRYPTED_CHUNK_SIZE

    fun chunksOfPart(plainSize: Long, partIndex: Int): LongRange {
        require(partIndex in 0 until partCount(plainSize)) { "part index out of range" }
        val first = partIndex.toLong() * CofferFormat.CHUNKS_PER_PART
        val last = minOf(first + CofferFormat.CHUNKS_PER_PART, chunkCount(plainSize)) - 1
        return first..last
    }

    fun encryptedPartSize(plainSize: Long, partIndex: Int): Long =
        chunksOfPart(plainSize, partIndex).sumOf {
            chunkPlainSize(plainSize, it).toLong() + CofferFormat.ENCRYPTED_CHUNK_OVERHEAD
        }

    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b
}

/** One chunk: `nonce(24) || XChaCha20-Poly1305(chunk)`, AD binds file, global index and the last flag. */
class ChunkCipher(private val sodium: Sodium, private val key: SecretKey, private val fileId: Id) {

    fun encrypt(chunkIndex: Long, isLast: Boolean, plain: ByteArray): ByteArray {
        val nonce = sodium.randomBytes(CofferFormat.NONCE_BYTES)
        val ad = AssociatedData.chunk(fileId, chunkIndex, isLast)
        return nonce + key.withBytes { sodium.aeadEncrypt(plain, ad, nonce, it) }
    }

    fun decrypt(chunkIndex: Long, isLast: Boolean, sealed: ByteArray): ByteArray {
        if (sealed.size < CofferFormat.ENCRYPTED_CHUNK_OVERHEAD) throw AuthenticationException("chunk too short")
        val nonce = sealed.copyOfRange(0, CofferFormat.NONCE_BYTES)
        val body = sealed.copyOfRange(CofferFormat.NONCE_BYTES, sealed.size)
        val ad = AssociatedData.chunk(fileId, chunkIndex, isLast)
        return key.withBytes { sodium.aeadDecrypt(body, ad, nonce, it) }
            ?: throw AuthenticationException("chunk $chunkIndex does not authenticate")
    }
}

/** BLAKE2b-256 of the plaintext, SPEC §3.6. */
class ContentHasher(sodium: Sodium) {
    private val stream = sodium.genericHashStream(CofferFormat.CONTENT_HASH_BYTES)

    fun update(data: ByteArray, length: Int = data.size) = stream.update(data, length)

    fun finish(): Bytes = Bytes.of(stream.finish())

    companion object {
        /** Reads [input] to the end. Returns the hash and the number of bytes read. */
        fun hash(sodium: Sodium, input: InputStream): Pair<Bytes, Long> {
            val hasher = ContentHasher(sodium)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hasher.update(buffer, n)
                total += n
            }
            return hasher.finish() to total
        }
    }
}

/** Thrown when the source does not have the size it was announced with, e.g. it changed between passes. */
class ContentSizeException(message: String) : IOException(message)

/**
 * Encrypts a file part by part (SPEC §6, step 3). Parts may be produced in any order,
 * which is what resuming an interrupted upload needs.
 */
class ContentEncryptor(sodium: Sodium, dek: SecretKey, fileId: Id, val plainSize: Long) {
    private val cipher = ChunkCipher(sodium, dek, fileId)
    private val lastChunk = ContentLayout.chunkCount(plainSize) - 1

    val partCount: Int = ContentLayout.partCount(plainSize)

    /**
     * Reads exactly this part's plaintext from [input] and writes the encrypted part to [output].
     * After the last part the input must be exhausted. [hasher], when given, sees the plaintext,
     * so a caller encrypting all parts in order can confirm the file still matches its content hash.
     */
    fun encryptPart(partIndex: Int, input: InputStream, output: OutputStream, hasher: ContentHasher? = null) {
        val buffer = ByteArray(CofferFormat.CHUNK_SIZE)
        for (chunk in ContentLayout.chunksOfPart(plainSize, partIndex)) {
            val length = ContentLayout.chunkPlainSize(plainSize, chunk)
            readFully(input, buffer, length)
            hasher?.update(buffer, length)
            output.write(cipher.encrypt(chunk, chunk == lastChunk, buffer.copyOf(length)))
        }
        buffer.fill(0)
        if (partIndex == partCount - 1 && input.read() != -1) {
            throw ContentSizeException("source is longer than $plainSize bytes")
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        // InputStream.readNBytes is API 33+; minSdk is 30.
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw ContentSizeException("source is shorter than $plainSize bytes")
            read += n
        }
    }
}

/** Decrypts whole parts or single chunks, SPEC §3.4, §6. */
class ContentDecryptor(sodium: Sodium, dek: SecretKey, fileId: Id, val plainSize: Long) {
    private val cipher = ChunkCipher(sodium, dek, fileId)
    private val lastChunk = ContentLayout.chunkCount(plainSize) - 1

    val partCount: Int = ContentLayout.partCount(plainSize)

    /** Random access: chunk `i` decrypts on its own. */
    fun decryptChunk(chunkIndex: Long, sealed: ByteArray): ByteArray {
        val expected = ContentLayout.chunkPlainSize(plainSize, chunkIndex) + CofferFormat.ENCRYPTED_CHUNK_OVERHEAD
        if (sealed.size != expected) throw AuthenticationException("chunk $chunkIndex has wrong size")
        return cipher.decrypt(chunkIndex, chunkIndex == lastChunk, sealed)
    }

    /** Reads one encrypted part from [input], which must hold exactly that part, and writes the plaintext. */
    fun decryptPart(partIndex: Int, input: InputStream, output: OutputStream, hasher: ContentHasher? = null) {
        for (chunk in ContentLayout.chunksOfPart(plainSize, partIndex)) {
            val sealed = ByteArray(ContentLayout.chunkPlainSize(plainSize, chunk) + CofferFormat.ENCRYPTED_CHUNK_OVERHEAD)
            var read = 0
            while (read < sealed.size) {
                val n = input.read(sealed, read, sealed.size - read)
                if (n < 0) throw EOFException("part $partIndex is truncated")
                read += n
            }
            val plain = decryptChunk(chunk, sealed)
            hasher?.update(plain)
            output.write(plain)
            plain.fill(0)
        }
        if (input.read() != -1) throw AuthenticationException("part $partIndex has trailing data")
    }
}
