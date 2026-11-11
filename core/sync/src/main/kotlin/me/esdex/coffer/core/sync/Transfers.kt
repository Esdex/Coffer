package me.esdex.coffer.core.sync

import android.annotation.SuppressLint
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.ContentEncryptor
import me.esdex.coffer.core.crypto.ContentDecryptor
import me.esdex.coffer.core.crypto.ContentHasher
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.ContentSizeException
import me.esdex.coffer.core.crypto.FileKeys
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.Padme
import me.esdex.coffer.core.crypto.format.BlobPayload
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.crypto.format.LocalUpload
import me.esdex.coffer.core.crypto.format.MimeTypes
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Where an upload reads from. Opened twice: once to hash, once to encrypt (SPEC §6). */
interface UploadSource {
    fun open(): InputStream
}

class SourceChangedException : IOException("the file changed while it was being uploaded")
class NotEnoughSpaceException(val needed: Long) : IOException("not enough free space for $needed bytes")
class MissingPartException(val partIndex: Int) : IOException("part $partIndex is not in Saved Messages")
class ContentMismatchException : IOException("downloaded content does not match its hash")

/** The plaintext length of one part: 512 chunks, the last part shorter. */
internal fun partPlainSize(plainSize: Long, partIndex: Int): Long {
    val partBytes = CofferFormat.CHUNKS_PER_PART.toLong() * CofferFormat.CHUNK_SIZE
    val start = partIndex * partBytes
    return (minOf(plainSize, start + partBytes) - start).coerceAtLeast(0)
}

/** Deleting a file (SPEC §5.3): the operation first, so other devices do not mistake it for damage. */
class FileDeleter(private val storage: StorageBackend, private val index: VaultIndex) {

    suspend fun delete(keys: VaultKeys, fileId: Id, now: Long) =
        deleteWith(fileId, keys.meta.encodeOp(Op.FileDelete(Id.random(keys.sodium), now, fileId)))

    /** With a `file.delete` caption made earlier, so this works while the vault is locked. */
    suspend fun deleteWith(fileId: Id, deleteOp: String) {
        storage.sendText(deleteOp)
        index.markDeleted(fileId)
        val parts = index.parts(fileId).values
        if (parts.isNotEmpty()) storage.delete(parts)
        index.removeFile(fileId)
    }
}

/**
 * Step 2 of an upload (SPEC §6): needs the unlocked vault. [padSizes] pads the file's content so its
 * exact size does not show (SPEC §4.5); the user's setting, on by default.
 */
class UploadPreparer(
    private val keys: VaultKeys,
    private val index: VaultIndex,
    private val padSizes: Boolean = true,
    private val clock: () -> Long,
) {
    private val sodium: Sodium get() = keys.sodium

    class Prepared(val upload: PendingUpload, val keys: TransferKeys)

    /**
     * Hashes [source] (first pass), makes the file's key and every caption, and records the upload.
     * [request] is [upload]'s opened sealed request: the name and type to store.
     */
    suspend fun prepare(upload: PendingUpload, request: LocalUpload, source: UploadSource): Prepared {
        val (hash, size) = source.open().use { ContentHasher.hash(sodium, it) }
        if (size > CofferFormat.MAX_PLAIN_SIZE) throw ContentSizeException("file is larger than the format allows")
        val fileId = Id.random(sodium)
        val dek = FileKeys.generateDek(sodium)
        val now = clock()
        val name = FileNames.fitForImport(request.name).takeIf(FileNames::isValid) ?: FALLBACK_NAME
        val meta = FileMeta(name, request.folderId, MimeTypes.normalize(request.mime), now, now, hash, keys.wrapDek(dek, fileId))
        val partCount = ContentLayout.partCount(Padme.contentSize(size, padSizes))
        val captions = (0 until partCount).map { part ->
            keys.meta.encodeBlob(BlobPayload(fileId, part, partCount, size, if (part == 0) meta else null, padSizes))
        }
        val abandon = keys.meta.encodeOp(Op.FileDelete(Id.random(sodium), now, fileId))
        // The upload first: a file row at UPLOADING with no upload is taken for a stale one and repaired.
        val prepared = PendingUpload(upload.uploadId, upload.sealedRequest, fileId, captions, abandon)
        index.putUpload(prepared)
        index.putFile(fileId, size, partCount, keys.local.sealFileMeta(fileId, meta), FileStatus.UPLOADING, padSizes)
        return Prepared(prepared, TransferKeys(dek, hash))
    }

    private companion object {
        const val FALLBACK_NAME = "file"
    }
}

/**
 * Step 3 of an upload (SPEC §6): needs only the file's DEK. Each part is encrypted to a temporary file,
 * sent, and the temporary file deleted. Parts already in Saved Messages are skipped, so an interrupted
 * upload resumes where it stopped. The second pass re-hashes the whole source; if it no longer matches
 * what part 0's caption promises, the upload is abandoned rather than leave a file that fails to verify.
 */
// usableSpace leaves out cache the system could clear: an upload refused for space it might have had is
// better than one that runs out of it in the middle of a part.
/** What a transfer is busy with, for whoever shows its progress. */
enum class TransferStage {
    /**
     * Reading the source with nothing on the wire: hashing parts already sent before a resume, or
     * encrypting the next part. A 512 MiB part takes a while, and a bar that does not move looks stuck.
     */
    PREPARING,

    /** Bytes going to or coming from Telegram. */
    MOVING,
}

@SuppressLint("UsableSpace")
class UploadRunner(
    private val sodium: Sodium,
    private val storage: StorageBackend,
    private val index: VaultIndex,
    private val tempDir: File,
    private val freeSpace: () -> Long = { tempDir.usableSpace },
) {
    suspend fun run(
        upload: PendingUpload,
        keys: TransferKeys,
        source: UploadSource,
        onProgress: (done: Long, total: Long, stage: TransferStage) -> Unit = { _, _, _ -> },
    ) {
        val fileId = checkNotNull(upload.fileId) { "upload not prepared" }
        val file = checkNotNull(index.file(fileId)) { "upload has no index entry" }
        val plainSize = file.plainSize
        val encryptor = ContentEncryptor(sodium, keys.dek, fileId, plainSize, file.padded)
        val sizes = (0 until encryptor.partCount).map { ContentLayout.encryptedPartSize(file.contentSize, it) }
        val total = sizes.sum()
        val hasher = ContentHasher(sodium)
        val sent = index.parts(fileId)
        var done = sizes.filterIndexed { part, _ -> part in sent }.sum()
        onProgress(done, total, TransferStage.PREPARING)

        source.open().buffered(BUFFER).use { input ->
            for (part in 0 until encryptor.partCount) {
                if (part in sent) {
                    hashOnly(input, partPlainSize(plainSize, part), hasher)
                    continue
                }
                onProgress(done, total, TransferStage.PREPARING)
                // Created first: File.usableSpace reports 0 for a path that does not exist.
                tempDir.mkdirs()
                val needed = sizes[part] + SPACE_MARGIN
                if (freeSpace() < needed) throw NotEnoughSpaceException(needed)
                val temp = File(tempDir, "cfr_${hex(sodium.randomBytes(6))}.bin")
                try {
                    try {
                        temp.outputStream().buffered(BUFFER).use { encryptor.encryptPart(part, input, it, hasher) }
                    } catch (_: ContentSizeException) {
                        // Shorter or longer than when it was hashed: the file changed.
                        throw abandon(upload, fileId)
                    }
                    val message = storage.sendBlob(temp, upload.captions[part]) { onProgress(done + it.done, total, TransferStage.MOVING) }
                    index.putPart(fileId, part, message.id)
                } finally {
                    temp.delete()
                }
                done += sizes[part]
                onProgress(done, total, TransferStage.MOVING)
            }
        }
        if (hasher.finish() != keys.contentHash) throw abandon(upload, fileId)
        index.setStatus(fileId, FileStatus.READY)
        index.removeUpload(upload.uploadId)
    }

    /** Takes back everything sent so far. */
    suspend fun cancel(upload: PendingUpload) {
        val fileId = upload.fileId
        val op = upload.abandonOp
        if (fileId != null && op != null) {
            if (index.parts(fileId).isEmpty()) index.removeFile(fileId) else FileDeleter(storage, index).deleteWith(fileId, op)
        }
        index.removeUpload(upload.uploadId)
    }

    private suspend fun abandon(upload: PendingUpload, fileId: Id): SourceChangedException {
        FileDeleter(storage, index).deleteWith(fileId, checkNotNull(upload.abandonOp))
        index.removeUpload(upload.uploadId)
        return SourceChangedException()
    }

    private fun hashOnly(input: InputStream, length: Long, hasher: ContentHasher) {
        val buffer = ByteArray(CofferFormat.CHUNK_SIZE)
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw SourceChangedException()
            hasher.update(buffer, n)
            left -= n
        }
        buffer.fill(0)
    }

    private companion object {
        const val BUFFER = 1 shl 16

        // Room for TDLib's own bookkeeping next to the encrypted part.
        const val SPACE_MARGIN = 64L * 1024 * 1024

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Downloads, decrypts and checks a file (SPEC §6, "Скачивание / экспорт"). Needs only the DEK. Each part
 * is handed to [partRead] once read; by default its local copy is dropped, the app keeps it in its cache.
 */
class DownloadRunner(
    private val sodium: Sodium,
    private val storage: StorageBackend,
    private val index: VaultIndex,
    private val partRead: suspend (MessageId) -> Unit = storage::releaseBlob,
) {

    suspend fun run(fileId: Id, keys: TransferKeys, output: OutputStream, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val file = checkNotNull(index.file(fileId)) { "file not in the index" }
        val decryptor = ContentDecryptor(sodium, keys.dek, fileId, file.plainSize, file.padded)
        val sizes = (0 until file.partCount).map { ContentLayout.encryptedPartSize(file.contentSize, it) }
        val total = sizes.sum()
        val hasher = ContentHasher(sodium)
        var done = 0L
        for (part in 0 until file.partCount) {
            val messageId = file.parts[part] ?: throw MissingPartException(part)
            try {
                storage.openBlob(messageId, onProgress = { onProgress(done + it.done, total) }).buffered(1 shl 16).use {
                    decryptor.decryptPart(part, it, output, hasher)
                }
            } finally {
                partRead(messageId)
            }
            done += sizes[part]
            onProgress(done, total)
        }
        output.flush()
        if (hasher.finish() != keys.contentHash) throw ContentMismatchException()
    }
}

/**
 * Brings every part of a file onto the phone whole, as Telegram holds it, for the file cache ("Cache
 * on this phone"). Nothing is decrypted, so no key is needed and it runs while the vault is locked.
 * A part already whole is not asked for again, so a stopped run picks up where it was; every part, new
 * or not, is handed to [partDone] to be written down as just used.
 */
class PrefetchRunner(
    private val storage: StorageBackend,
    private val index: VaultIndex,
    private val partDone: suspend (MessageId) -> Unit,
) {
    suspend fun run(fileId: Id, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val file = checkNotNull(index.file(fileId)) { "file not in the index" }
        val sizes = (0 until file.partCount).map { ContentLayout.encryptedPartSize(file.contentSize, it) }
        val total = sizes.sum()
        var done = 0L
        onProgress(done, total)
        for (part in 0 until file.partCount) {
            val messageId = file.parts[part] ?: throw MissingPartException(part)
            if (storage.localBytes(messageId) < sizes[part]) {
                storage.openBlob(messageId, onProgress = { onProgress(done + it.done, total) }).close()
            }
            partDone(messageId)
            done += sizes[part]
            onProgress(done, total)
        }
    }
}

/** The DEK and content hash of an indexed file, for a transfer to hold. Needs the unlocked vault. */
fun VaultKeys.transferKeys(fileId: Id, sealedMeta: ByteArray): TransferKeys? {
    val meta = local.openFileMeta(fileId, sealedMeta) ?: return null
    return TransferKeys(unwrapDek(meta, fileId), meta.contentHash)
}
