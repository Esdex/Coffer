package me.esdex.coffer.core.crypto.format

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborLabel
import me.esdex.coffer.core.crypto.Argon2Params
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.AssociatedData
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.sodium.Sodium

/** Vault header caption, SPEC §4.1: `coffer:v1:vault:<base64url(CBOR)>`, no encryption. */
object VaultHeaderCodec {

    fun encode(header: VaultHeader): String {
        val cbor = CanonicalCbor.encode(
            VaultHeaderCbor.serializer(),
            VaultHeaderCbor(
                format = VaultHeader.FORMAT,
                createdAt = header.createdAt,
                salt = header.salt.toByteArray(),
                argonOps = header.argon.opsLimit,
                argonMem = header.argon.memLimitBytes,
                wrappedMaster = header.wrappedMaster.toByteArray(),
                masterId = header.masterId.toByteArray(),
            ),
        )
        return Captions.format(RecordKind.VAULT, Base64Url.encode(cbor))
    }

    /** Null when the record is not a well-formed v1 header. */
    fun decode(record: RawRecord): VaultHeader? {
        if (record.kind != RecordKind.VAULT) return null
        val bytes = Base64Url.decode(record.body) ?: return null
        val cbor = CanonicalCbor.decode(VaultHeaderCbor.serializer(), bytes) ?: return null
        if (cbor.format != VaultHeader.FORMAT) return null
        return validated {
            VaultHeader(
                createdAt = cbor.createdAt,
                salt = Bytes.of(cbor.salt),
                argon = Argon2Params(cbor.argonOps, cbor.argonMem),
                wrappedMaster = Bytes.of(cbor.wrappedMaster),
                masterId = Bytes.of(cbor.masterId),
            )
        }
    }
}

/**
 * Encrypted captions and texts, SPEC §4: `coffer:v1:<kind>:<enc>` with
 * `<enc> = base64url(nonce(24) || XChaCha20-Poly1305(MetaKey, CBOR, AD = "coffer/v1/<kind>"))`.
 *
 * Decoders return null for anything that does not authenticate or breaks the format: such a record is
 * either from another vault or damaged, and sync treats both the same way.
 */
class MetaCodec(private val sodium: Sodium, private val metaKey: SecretKey) {

    fun encodeBlob(payload: BlobPayload): String {
        val meta = payload.meta
        val cbor = if (meta == null) {
            CanonicalCbor.encode(
                BlobPartCbor.serializer(),
                BlobPartCbor(
                    fileId = payload.fileId.toByteArray(),
                    partIndex = payload.partIndex,
                    partCount = payload.partCount,
                    chunkSize = CofferFormat.CHUNK_SIZE,
                    plainSize = payload.plainSize,
                ),
            )
        } else {
            CanonicalCbor.encode(
                BlobFirstPartCbor.serializer(),
                BlobFirstPartCbor(
                    fileId = payload.fileId.toByteArray(),
                    partIndex = payload.partIndex,
                    partCount = payload.partCount,
                    chunkSize = CofferFormat.CHUNK_SIZE,
                    plainSize = payload.plainSize,
                    name = meta.name,
                    folderId = meta.folderId?.toByteArray(),
                    mime = meta.mime,
                    createdAt = meta.createdAt,
                    modifiedAt = meta.modifiedAt,
                    contentHash = meta.contentHash.toByteArray(),
                    wrappedDek = meta.wrappedDek.toByteArray(),
                ),
            )
        }
        return seal(RecordKind.BLOB, cbor)
    }

    fun decodeBlob(record: RawRecord): BlobPayload? {
        val bytes = open(RecordKind.BLOB, record) ?: return null
        val head = CanonicalCbor.sniff(BlobHeadCbor.serializer(), bytes) ?: return null
        return if (head.partIndex == 0) {
            val c = CanonicalCbor.decode(BlobFirstPartCbor.serializer(), bytes) ?: return null
            if (c.chunkSize != CofferFormat.CHUNK_SIZE) return null
            validated {
                BlobPayload(
                    fileId = Id.of(c.fileId),
                    partIndex = c.partIndex,
                    partCount = c.partCount,
                    plainSize = c.plainSize,
                    meta = FileMeta(
                        name = c.name,
                        folderId = c.folderId?.let(Id::of),
                        mime = c.mime,
                        createdAt = c.createdAt,
                        modifiedAt = c.modifiedAt,
                        contentHash = Bytes.of(c.contentHash),
                        wrappedDek = Bytes.of(c.wrappedDek),
                    ),
                )
            }
        } else {
            val c = CanonicalCbor.decode(BlobPartCbor.serializer(), bytes) ?: return null
            if (c.chunkSize != CofferFormat.CHUNK_SIZE) return null
            validated { BlobPayload(Id.of(c.fileId), c.partIndex, c.partCount, c.plainSize, meta = null) }
        }
    }

    fun encodeOp(op: Op): String {
        val id = op.opId.toByteArray()
        val cbor = when (op) {
            is Op.FolderCreate -> CanonicalCbor.encode(
                FolderCreateCbor.serializer(),
                FolderCreateCbor(id, op.type, op.ts, op.folderId.toByteArray(), op.parentId?.toByteArray(), op.name),
            )
            is Op.FolderRename -> CanonicalCbor.encode(
                FolderRenameCbor.serializer(),
                FolderRenameCbor(id, op.type, op.ts, op.folderId.toByteArray(), op.name),
            )
            is Op.FolderMove -> CanonicalCbor.encode(
                FolderMoveCbor.serializer(),
                FolderMoveCbor(id, op.type, op.ts, op.folderId.toByteArray(), op.parentId?.toByteArray()),
            )
            is Op.FolderDelete -> CanonicalCbor.encode(
                FolderDeleteCbor.serializer(),
                FolderDeleteCbor(id, op.type, op.ts, op.folderId.toByteArray()),
            )
            is Op.FileRename -> CanonicalCbor.encode(
                FileRenameCbor.serializer(),
                FileRenameCbor(id, op.type, op.ts, op.fileId.toByteArray(), op.name),
            )
            is Op.FileMove -> CanonicalCbor.encode(
                FileMoveCbor.serializer(),
                FileMoveCbor(id, op.type, op.ts, op.fileId.toByteArray(), op.folderId?.toByteArray()),
            )
            is Op.FileDelete -> CanonicalCbor.encode(
                FileDeleteCbor.serializer(),
                FileDeleteCbor(id, op.type, op.ts, op.fileId.toByteArray()),
            )
        }
        return seal(RecordKind.OP, cbor)
    }

    fun decodeOp(record: RawRecord): Op? {
        val bytes = open(RecordKind.OP, record) ?: return null
        val type = CanonicalCbor.sniff(OpHeadCbor.serializer(), bytes)?.type ?: return null
        return validated {
            when (type) {
                "folder.create" -> CanonicalCbor.decode(FolderCreateCbor.serializer(), bytes)
                    ?.takeIf { it.type == type }
                    ?.let { Op.FolderCreate(Id.of(it.opId), it.ts, Id.of(it.folderId), it.parentId?.let(Id::of), it.name) }
                "folder.rename" -> CanonicalCbor.decode(FolderRenameCbor.serializer(), bytes)
                    ?.takeIf { it.type == type }
                    ?.let { Op.FolderRename(Id.of(it.opId), it.ts, Id.of(it.folderId), it.name) }
                "folder.move" -> CanonicalCbor.decode(FolderMoveCbor.serializer(), bytes)
                    ?.takeIf { it.type == type }
                    ?.let { Op.FolderMove(Id.of(it.opId), it.ts, Id.of(it.folderId), it.parentId?.let(Id::of)) }
                "folder.delete" -> CanonicalCbor.decode(FolderDeleteCbor.serializer(), bytes)
                    ?.takeIf { it.type == type }
                    ?.let { Op.FolderDelete(Id.of(it.opId), it.ts, Id.of(it.folderId)) }
                "file.rename" -> CanonicalCbor.decode(FileRenameCbor.serializer(), bytes)
                    ?.takeIf { it.type == type }
                    ?.let { Op.FileRename(Id.of(it.opId), it.ts, Id.of(it.fileId), it.name) }
                "file.move" -> CanonicalCbor.decode(FileMoveCbor.serializer(), bytes)
                    ?.takeIf { it.type == type }
                    ?.let { Op.FileMove(Id.of(it.opId), it.ts, Id.of(it.fileId), it.folderId?.let(Id::of)) }
                "file.delete" -> CanonicalCbor.decode(FileDeleteCbor.serializer(), bytes)
                    ?.takeIf { it.type == type }
                    ?.let { Op.FileDelete(Id.of(it.opId), it.ts, Id.of(it.fileId)) }
                else -> null
            }
        }
    }

    fun encodeIndex(payload: IndexPayload): String = seal(
        RecordKind.INDEX,
        CanonicalCbor.encode(
            IndexCbor.serializer(),
            IndexCbor(payload.snapshotId.toByteArray(), payload.lastAppliedMessageId, payload.createdAt, payload.plainSize),
        ),
    )

    fun decodeIndex(record: RawRecord): IndexPayload? {
        val bytes = open(RecordKind.INDEX, record) ?: return null
        val c = CanonicalCbor.decode(IndexCbor.serializer(), bytes) ?: return null
        return validated { IndexPayload(Id.of(c.snapshotId), c.lastAppliedMessageId, c.createdAt, c.plainSize) }
    }

    private fun seal(kind: RecordKind, cbor: ByteArray): String {
        val nonce = sodium.randomBytes(CofferFormat.NONCE_BYTES)
        val sealed = metaKey.withBytes { sodium.aeadEncrypt(cbor, AssociatedData.meta(kind), nonce, it) }
        return Captions.format(kind, Base64Url.encode(nonce + sealed))
    }

    private fun open(kind: RecordKind, record: RawRecord): ByteArray? {
        if (record.kind != kind) return null
        val raw = Base64Url.decode(record.body) ?: return null
        if (raw.size < CofferFormat.NONCE_BYTES + CofferFormat.TAG_BYTES) return null
        val nonce = raw.copyOfRange(0, CofferFormat.NONCE_BYTES)
        val body = raw.copyOfRange(CofferFormat.NONCE_BYTES, raw.size)
        return metaKey.withBytes { sodium.aeadDecrypt(body, AssociatedData.meta(kind), nonce, it) }
    }
}

/** Runs a model constructor on decoded input; its validation failures mean "not a valid record". */
internal inline fun <T> validated(block: () -> T?): T? = try {
    block()
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Deterministic CBOR (RFC 8949 §4.2.1) with integer keys, SPEC §4.0.
 * Map keys come out in declaration order, so every surrogate class below declares its labels ascending.
 */
internal object CanonicalCbor {
    private val strict = Cbor {
        preferCborLabelsOverNames = true
        useDefiniteLengthEncoding = true
        alwaysUseByteString = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    // Only for reading the field that decides which full class to decode with.
    private val lenient = Cbor(from = strict) { ignoreUnknownKeys = true }

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = strict.encodeToByteArray(serializer, value)

    /** Accepts only bytes that re-encode to themselves: no duplicate keys, padding or alternative encodings. */
    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T? {
        val value = parse { strict.decodeFromByteArray(serializer, bytes) } ?: return null
        return if (strict.encodeToByteArray(serializer, value).contentEquals(bytes)) value else null
    }

    fun <T> sniff(serializer: KSerializer<T>, bytes: ByteArray): T? = parse { lenient.decodeFromByteArray(serializer, bytes) }

    // Input here has passed authentication but is still treated as untrusted: any parser failure is "invalid".
    private inline fun <T> parse(block: () -> T): T? = try {
        block()
    } catch (_: RuntimeException) {
        null
    }
}

@Serializable
private class VaultHeaderCbor(
    @CborLabel(1) val format: Int,
    @CborLabel(2) val createdAt: Long,
    @CborLabel(3) val salt: ByteArray,
    @CborLabel(4) val argonOps: Long,
    @CborLabel(5) val argonMem: Long,
    @CborLabel(6) val wrappedMaster: ByteArray,
    @CborLabel(7) val masterId: ByteArray,
)

@Serializable
private class BlobHeadCbor(
    @CborLabel(2) val partIndex: Int,
)

@Serializable
private class BlobPartCbor(
    @CborLabel(1) val fileId: ByteArray,
    @CborLabel(2) val partIndex: Int,
    @CborLabel(3) val partCount: Int,
    @CborLabel(4) val chunkSize: Int,
    @CborLabel(5) val plainSize: Long,
)

@Serializable
private class BlobFirstPartCbor(
    @CborLabel(1) val fileId: ByteArray,
    @CborLabel(2) val partIndex: Int,
    @CborLabel(3) val partCount: Int,
    @CborLabel(4) val chunkSize: Int,
    @CborLabel(5) val plainSize: Long,
    @CborLabel(6) val name: String,
    // No default value: null is always written, and means root (SPEC §4.0).
    @CborLabel(7) val folderId: ByteArray?,
    @CborLabel(8) val mime: String,
    @CborLabel(9) val createdAt: Long,
    @CborLabel(10) val modifiedAt: Long,
    @CborLabel(11) val contentHash: ByteArray,
    @CborLabel(12) val wrappedDek: ByteArray,
)

@Serializable
private class OpHeadCbor(
    @CborLabel(2) val type: String,
)

@Serializable
private class FolderCreateCbor(
    @CborLabel(1) val opId: ByteArray,
    @CborLabel(2) val type: String,
    @CborLabel(3) val ts: Long,
    @CborLabel(5) val folderId: ByteArray,
    @CborLabel(6) val parentId: ByteArray?,
    @CborLabel(7) val name: String,
)

@Serializable
private class FolderRenameCbor(
    @CborLabel(1) val opId: ByteArray,
    @CborLabel(2) val type: String,
    @CborLabel(3) val ts: Long,
    @CborLabel(5) val folderId: ByteArray,
    @CborLabel(7) val name: String,
)

@Serializable
private class FolderMoveCbor(
    @CborLabel(1) val opId: ByteArray,
    @CborLabel(2) val type: String,
    @CborLabel(3) val ts: Long,
    @CborLabel(5) val folderId: ByteArray,
    @CborLabel(6) val parentId: ByteArray?,
)

@Serializable
private class FolderDeleteCbor(
    @CborLabel(1) val opId: ByteArray,
    @CborLabel(2) val type: String,
    @CborLabel(3) val ts: Long,
    @CborLabel(5) val folderId: ByteArray,
)

@Serializable
private class FileRenameCbor(
    @CborLabel(1) val opId: ByteArray,
    @CborLabel(2) val type: String,
    @CborLabel(3) val ts: Long,
    @CborLabel(4) val fileId: ByteArray,
    @CborLabel(7) val name: String,
)

@Serializable
private class FileMoveCbor(
    @CborLabel(1) val opId: ByteArray,
    @CborLabel(2) val type: String,
    @CborLabel(3) val ts: Long,
    @CborLabel(4) val fileId: ByteArray,
    @CborLabel(5) val folderId: ByteArray?,
)

@Serializable
private class FileDeleteCbor(
    @CborLabel(1) val opId: ByteArray,
    @CborLabel(2) val type: String,
    @CborLabel(3) val ts: Long,
    @CborLabel(4) val fileId: ByteArray,
)

@Serializable
private class IndexCbor(
    @CborLabel(1) val snapshotId: ByteArray,
    @CborLabel(2) val lastAppliedMessageId: Long,
    @CborLabel(3) val createdAt: Long,
    @CborLabel(4) val plainSize: Long,
)
