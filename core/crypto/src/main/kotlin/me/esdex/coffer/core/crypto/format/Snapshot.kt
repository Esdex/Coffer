package me.esdex.coffer.core.crypto.format

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.ContentDecryptor
import me.esdex.coffer.core.crypto.ContentEncryptor
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.Padme
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.sodium.Sodium
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

// The index snapshot, SPEC §4.4. Lists are sorted by id and ids are unique, so one state has one encoding.

data class SnapshotFolder(val folderId: Id, val parentId: Id?, val name: String) {
    init {
        require(FileNames.isValid(name)) { "invalid name" }
    }
}

/** A file with its current name and folder, and the message ids of the parts known so far. */
data class SnapshotFile(
    val fileId: Id,
    val plainSize: Long,
    val meta: FileMeta,
    val parts: Map<Int, Long>,
    val damaged: Boolean,
    /** Its content runs on in zeros to the padded size (SPEC §4.5); only a version 2 snapshot says so. */
    val padded: Boolean = false,
) {
    val partCount: Int get() = ContentLayout.partCount(Padme.contentSize(plainSize, padded))

    init {
        require(plainSize in 0..CofferFormat.MAX_PLAIN_SIZE) { "size out of range" }
        require(parts.all { (index, id) -> index in 0 until partCount && id > 0 }) { "part out of range" }
    }
}

/** Parts whose part 0 has not been seen (SPEC §5.1). Their part count is not known yet. */
data class SnapshotOrphan(val fileId: Id, val parts: Map<Int, Long>) {
    init {
        require(parts.isNotEmpty()) { "no parts" }
        require(parts.all { (index, id) -> index >= 0 && id > 0 }) { "part out of range" }
    }
}

data class IndexState(
    val lastAppliedMessageId: Long,
    val folders: List<SnapshotFolder>,
    val files: List<SnapshotFile>,
    val orphans: List<SnapshotOrphan>,
    val deletedFiles: List<Id>,
    val deletedFolders: List<Id>,
) {
    init {
        require(lastAppliedMessageId >= 0) { "negative message id" }
        require(folders.map { it.folderId }.distinct().size == folders.size) { "duplicate folder" }
        require(files.map { it.fileId }.distinct().size == files.size) { "duplicate file" }
        require(orphans.map { it.fileId }.distinct().size == orphans.size) { "duplicate orphan" }
        require(deletedFiles.distinct().size == deletedFiles.size) { "duplicate deleted file" }
        require(deletedFolders.distinct().size == deletedFolders.size) { "duplicate deleted folder" }
        val known = files.map { it.fileId }.toSet() + deletedFiles
        require(orphans.none { it.fileId in known }) { "orphan of a known file" }
    }
}

/**
 * Encodes and encrypts snapshots, SPEC §4.4: `deflate(CBOR(state))`, raw deflate, encrypted like file
 * content (§3.4) with IndexKey and the snapshot id. A snapshot is a single part. A version 2 snapshot is
 * [padded][encode] like a file (§4.5), as its size would otherwise tell how many files the vault holds,
 * and may mark padded files; a version 1 snapshot may not.
 */
class SnapshotCodec(private val sodium: Sodium, private val indexKey: SecretKey) {

    /** The encrypted document and the length of the deflated plaintext, for the caption's `plain_size`. */
    class Sealed(val bytes: ByteArray, val plainSize: Long)

    fun encode(snapshotId: Id, state: IndexState, padded: Boolean = true): Sealed {
        require(padded || state.files.none { it.padded }) { "a version 1 snapshot cannot hold padded files" }
        val deflated = deflate(stateCbor(state))
        require(deflated.size <= MAX_DEFLATED) { "snapshot too large" }
        val out = ByteArrayOutputStream()
        ContentEncryptor(sodium, indexKey, snapshotId, deflated.size.toLong(), padded).encryptPart(0, ByteArrayInputStream(deflated), out)
        return Sealed(out.toByteArray(), deflated.size.toLong())
    }

    /**
     * Null if the document does not decrypt with this vault's IndexKey, does not inflate, or breaks the
     * format: such a snapshot is treated as damaged and an older one, or a full rescan, is used instead.
     * [padded] is what its caption's version says.
     */
    fun decode(snapshotId: Id, plainSize: Long, input: InputStream, padded: Boolean, maxInflated: Int = MAX_INFLATED): IndexState? {
        if (plainSize !in 0..MAX_DEFLATED.toLong()) return null
        // One part: its padded size must still be a single part.
        if (Padme.contentSize(plainSize, padded) > MAX_DEFLATED) return null
        val deflated = ByteArrayOutputStream()
        try {
            ContentDecryptor(sodium, indexKey, snapshotId, plainSize, padded).decryptPart(0, input, deflated)
        } catch (_: Exception) {
            return null
        }
        val cbor = inflate(deflated.toByteArray(), maxInflated) ?: return null
        return decodeState(cbor, version2 = padded)
    }

    /** The CBOR before deflate; public for the format's test vectors. */
    fun stateCbor(state: IndexState): ByteArray = CanonicalCbor.encode(StateCbor.serializer(), state.toCbor())

    /** Strict reading of the CBOR after inflate (SPEC §4.0, §4.4); only [version2] may mark padded files. */
    fun decodeState(cbor: ByteArray, version2: Boolean = true): IndexState? {
        val c = CanonicalCbor.decode(StateCbor.serializer(), cbor) ?: return null
        if (!version2 && c.files.any { it.padded != null }) return null
        return validated { c.toModel() }
    }

    companion object {
        /** SPEC §4.4: a larger inflated state is refused. */
        const val MAX_INFLATED = 256 * 1024 * 1024

        // One part, and an Int-sized array.
        private const val MAX_DEFLATED = MAX_INFLATED

        fun deflate(bytes: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
            try {
                deflater.setInput(bytes)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(1 shl 16)
                while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }

        /** Null for anything but exactly one complete raw deflate stream that inflates to at most [max] bytes. */
        fun inflate(bytes: ByteArray, max: Int): ByteArray? {
            val inflater = Inflater(true)
            try {
                inflater.setInput(bytes)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(1 shl 16)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                    if (out.size() + n > max) return null
                    out.write(buffer, 0, n)
                }
                return if (inflater.remaining == 0) out.toByteArray() else null
            } catch (_: DataFormatException) {
                return null
            } finally {
                inflater.end()
            }
        }
    }
}

private fun Id.unsignedCompare(other: Id): Int {
    val a = toByteArray()
    val b = other.toByteArray()
    for (i in a.indices) {
        val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        if (d != 0) return d
    }
    return 0
}

private val byId = Comparator<Id> { a, b -> a.unsignedCompare(b) }

private fun Map<Int, Long>.sortedParts(): Map<Int, Long> = toSortedMap()

private fun IndexState.toCbor() = StateCbor(
    lastApplied = lastAppliedMessageId,
    folders = folders.sortedWith(compareBy(byId) { it.folderId }).map {
        FolderCbor(it.folderId.toByteArray(), it.parentId?.toByteArray(), it.name)
    },
    files = files.sortedWith(compareBy(byId) { it.fileId }).map {
        FileCbor(
            fileId = it.fileId.toByteArray(),
            partCount = it.partCount,
            chunkSize = CofferFormat.CHUNK_SIZE,
            plainSize = it.plainSize,
            name = it.meta.name,
            folderId = it.meta.folderId?.toByteArray(),
            mime = it.meta.mime,
            createdAt = it.meta.createdAt,
            modifiedAt = it.meta.modifiedAt,
            contentHash = it.meta.contentHash.toByteArray(),
            wrappedDek = it.meta.wrappedDek.toByteArray(),
            parts = it.parts.sortedParts(),
            damaged = if (it.damaged) true else null,
            padded = if (it.padded) true else null,
        )
    },
    orphans = orphans.sortedWith(compareBy(byId) { it.fileId }).map { OrphanCbor(it.fileId.toByteArray(), it.parts.sortedParts()) },
    deletedFiles = deletedFiles.sortedWith(byId).map { it.toByteArray() },
    deletedFolders = deletedFolders.sortedWith(byId).map { it.toByteArray() },
)

// Re-encoding keeps the order it was given, so canonical order is checked here: strictly ascending,
// which also rules out duplicates.
private fun List<ByteArray>.ascending(): Boolean = map(Id::of).zipWithNext().all { (a, b) -> a.unsignedCompare(b) < 0 }

private fun Map<Int, Long>.ascending(): Boolean = keys.zipWithNext().all { (a, b) -> a < b }

private fun StateCbor.toModel(): IndexState {
    require(folders.map { it.folderId }.ascending()) { "folders not in order" }
    require(files.map { it.fileId }.ascending()) { "files not in order" }
    require(orphans.map { it.fileId }.ascending()) { "orphans not in order" }
    require(deletedFiles.ascending() && deletedFolders.ascending()) { "deleted ids not in order" }
    require(files.all { it.parts.ascending() } && orphans.all { it.parts.ascending() }) { "parts not in order" }
    return model()
}

private fun StateCbor.model() = IndexState(
    lastAppliedMessageId = lastApplied,
    folders = folders.map { SnapshotFolder(Id.of(it.folderId), it.parentId?.let(Id::of), it.name) },
    files = files.map {
        require(it.chunkSize == CofferFormat.CHUNK_SIZE) { "chunk size" }
        require(it.plainSize in 0..CofferFormat.MAX_PLAIN_SIZE) { "size out of range" }
        // Absent means not damaged, or not padded; an explicit false would be a second encoding of the same state.
        require(it.damaged != false) { "damaged written as false" }
        require(it.padded != false) { "padded written as false" }
        require(it.partCount == ContentLayout.partCount(Padme.contentSize(it.plainSize, it.padded == true))) { "part count does not match size" }
        SnapshotFile(
            fileId = Id.of(it.fileId),
            plainSize = it.plainSize,
            meta = FileMeta(
                it.name, it.folderId?.let(Id::of), it.mime, it.createdAt, it.modifiedAt,
                Bytes.of(it.contentHash), Bytes.of(it.wrappedDek),
            ),
            parts = it.parts,
            damaged = it.damaged == true,
            padded = it.padded == true,
        )
    },
    orphans = orphans.map { SnapshotOrphan(Id.of(it.fileId), it.parts) },
    deletedFiles = deletedFiles.map(Id::of),
    deletedFolders = deletedFolders.map(Id::of),
)

@Serializable
private class StateCbor(
    @CborLabel(1) val lastApplied: Long,
    @CborLabel(2) val folders: List<FolderCbor>,
    @CborLabel(3) val files: List<FileCbor>,
    @CborLabel(4) val orphans: List<OrphanCbor>,
    @CborLabel(5) val deletedFiles: List<ByteArray>,
    @CborLabel(6) val deletedFolders: List<ByteArray>,
)

@Serializable
private class FolderCbor(
    @CborLabel(1) val folderId: ByteArray,
    // No default value: null is always written, and means root (SPEC §4.0).
    @CborLabel(2) val parentId: ByteArray?,
    @CborLabel(3) val name: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class FileCbor(
    @CborLabel(1) val fileId: ByteArray,
    @CborLabel(3) val partCount: Int,
    @CborLabel(4) val chunkSize: Int,
    @CborLabel(5) val plainSize: Long,
    @CborLabel(6) val name: String,
    @CborLabel(7) val folderId: ByteArray?,
    @CborLabel(8) val mime: String,
    @CborLabel(9) val createdAt: Long,
    @CborLabel(10) val modifiedAt: Long,
    @CborLabel(11) val contentHash: ByteArray,
    @CborLabel(12) val wrappedDek: ByteArray,
    @CborLabel(13) val parts: Map<Int, Long>,
    @CborLabel(14) @EncodeDefault(EncodeDefault.Mode.NEVER) val damaged: Boolean? = null,
    @CborLabel(15) @EncodeDefault(EncodeDefault.Mode.NEVER) val padded: Boolean? = null,
)

@Serializable
private class OrphanCbor(
    @CborLabel(1) val fileId: ByteArray,
    @CborLabel(13) val parts: Map<Int, Long>,
)
