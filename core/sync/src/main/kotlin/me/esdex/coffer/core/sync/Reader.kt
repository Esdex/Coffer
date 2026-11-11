package me.esdex.coffer.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.esdex.coffer.core.crypto.ContentDecryptor
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import java.io.EOFException

/**
 * Any range of a vault file's plain text, for viewers to read from instead of a decrypted copy on
 * disk (SPEC §3.4: chunk `i` decrypts on its own). A chunk is fetched from its part with a ranged
 * download (TDLib keeps what it fetched, still encrypted), decrypted and checked by itself, and the
 * last few stay in memory, so a player reading forward or seeking a little does not fetch twice.
 * Each chunk is authenticated with the file id and its index, so a tampered or misplaced chunk is
 * refused; the whole-file hash is not, as nothing here reads the whole file.
 *
 * Holds [keys] but does not own them. [close] wipes the plain text kept and lets go of the downloads.
 */
class VaultFileReader(
    sodium: Sodium,
    private val storage: StorageBackend,
    private val file: IndexedFile,
    keys: TransferKeys,
    private val cachedChunks: Int = CACHED_CHUNKS,
) : AutoCloseable {
    val size: Long = file.plainSize

    // A padded file's chunks are laid out over its padded size; the zeros after the file are never read.
    private val decryptor = ContentDecryptor(sodium, keys.dek, file.fileId, file.plainSize, file.padded)

    // One fetch at a time; the chunks and the closed flag under the monitor, held only briefly, so
    // close() never waits on the network.
    private val fetching = Mutex()
    private val cache = LinkedHashMap<Long, ByteArray>(cachedChunks + 1, 1f, true)
    private val used = HashSet<MessageId>()
    private var closed = false

    /**
     * Up to [length] bytes from [position] into [buffer] at [offset]; fewer only at the end of the
     * file, and -1 from the end on.
     */
    suspend fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        require(position >= 0 && length >= 0 && offset >= 0 && offset + length <= buffer.size) { "bad range" }
        if (position >= size) return -1
        var done = 0
        while (done < length && position + done < size) {
            val at = position + done
            val chunkIndex = at / CofferFormat.CHUNK_SIZE
            val inChunk = (at % CofferFormat.CHUNK_SIZE).toInt()
            val plain = fetching.withLock { chunk(chunkIndex) }
            done += synchronized(cache) {
                check(!closed) { "reader closed" }
                val n = minOf(length - done, plain.size - inChunk)
                plain.copyInto(buffer, offset + done, inChunk, inChunk + n)
                n
            }
        }
        return done
    }

    private suspend fun chunk(index: Long): ByteArray {
        synchronized(cache) {
            check(!closed) { "reader closed" }
            cache[index]?.let { return it }
        }
        val part = ContentLayout.partOfChunk(index)
        val messageId = file.parts[part] ?: throw MissingPartException(part)
        val sealedSize = ContentLayout.chunkPlainSize(file.contentSize, index) + CofferFormat.ENCRYPTED_CHUNK_OVERHEAD
        synchronized(cache) { used += messageId }
        val sealed = ByteArray(sealedSize)
        storage.openBlob(messageId, ContentLayout.offsetInPart(index), sealedSize.toLong()).use { input ->
            var read = 0
            while (read < sealedSize) {
                val n = input.read(sealed, read, sealedSize - read)
                if (n < 0) throw EOFException("part $part ends inside chunk $index")
                read += n
            }
        }
        val plain = decryptor.decryptChunk(index, sealed)
        synchronized(cache) {
            if (closed) {
                plain.fill(0)
                throw IllegalStateException("reader closed")
            }
            cache[index] = plain
            if (cache.size > cachedChunks) {
                val eldest = cache.entries.first()
                eldest.value.fill(0)
                cache.remove(eldest.key)
            }
        }
        return plain
    }

    override fun close() {
        synchronized(cache) {
            closed = true
            cache.values.forEach { it.fill(0) }
            cache.clear()
        }
    }

    /** The parts this reader fetched from, for dropping TDLib's local copies once nothing reads them. */
    fun partsUsed(): Set<MessageId> = synchronized(cache) { used.toSet() }

    private companion object {
        /** 4 MiB of plain text: a little behind and ahead of a player's position. */
        const val CACHED_CHUNKS = 4
    }
}

/** Opens a reader on an indexed file; refuses a file that is not whole yet. */
fun VaultKeys.reader(storage: StorageBackend, file: IndexedFile, keys: TransferKeys): VaultFileReader {
    if (file.parts.size < file.partCount) throw MissingPartException((0 until file.partCount).first { it !in file.parts })
    return VaultFileReader(sodium, storage, file, keys)
}
