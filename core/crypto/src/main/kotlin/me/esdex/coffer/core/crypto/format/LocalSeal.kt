package me.esdex.coffer.core.crypto.format

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.sodium.Sodium

/**
 * Seals what this device keeps for itself: the decrypted metadata in the local index (SPEC §8), so file
 * and folder names cannot be read while the vault is locked. Not part of the Telegram format and never
 * sent anywhere; it can change with a database migration.
 *
 * `nonce(24) || XChaCha20-Poly1305(LocalKey, CBOR, AD = "coffer/local/v1/<file|folder|upload|target>" || id)`.
 * The id in the AD keeps one row's sealed metadata from being moved to another row.
 */
class LocalSealer(private val sodium: Sodium, private val localKey: SecretKey) {

    fun sealFileMeta(fileId: Id, meta: FileMeta): ByteArray {
        val cbor = CanonicalCbor.encode(
            LocalFileMetaCbor.serializer(),
            LocalFileMetaCbor(
                name = meta.name,
                folderId = meta.folderId?.toByteArray(),
                mime = meta.mime,
                createdAt = meta.createdAt,
                modifiedAt = meta.modifiedAt,
                contentHash = meta.contentHash.toByteArray(),
                wrappedDek = meta.wrappedDek.toByteArray(),
            ),
        )
        return seal(cbor, fileAd(fileId))
    }

    /** Null if [sealed] is damaged, from another vault, or belongs to another file. */
    fun openFileMeta(fileId: Id, sealed: ByteArray): FileMeta? {
        val cbor = open(sealed, fileAd(fileId)) ?: return null
        val c = CanonicalCbor.decode(LocalFileMetaCbor.serializer(), cbor) ?: return null
        return validated {
            FileMeta(
                name = c.name,
                folderId = c.folderId?.let(Id::of),
                mime = c.mime,
                createdAt = c.createdAt,
                modifiedAt = c.modifiedAt,
                contentHash = Bytes.of(c.contentHash),
                wrappedDek = Bytes.of(c.wrappedDek),
            )
        }
    }

    /** A folder's name and parent, as the operation log left them (SPEC §5.2). */
    fun sealFolder(folderId: Id, folder: LocalFolder): ByteArray = seal(
        CanonicalCbor.encode(LocalFolderCbor.serializer(), LocalFolderCbor(folder.name, folder.parentId?.toByteArray())),
        FOLDER_AD + folderId.toByteArray(),
    )

    fun openFolder(folderId: Id, sealed: ByteArray): LocalFolder? {
        val cbor = open(sealed, FOLDER_AD + folderId.toByteArray()) ?: return null
        val c = CanonicalCbor.decode(LocalFolderCbor.serializer(), cbor) ?: return null
        return validated { LocalFolder(c.name, c.parentId?.let(Id::of)) }
    }

    /** A queued upload: where to read and under which name, sealed like file metadata (picked URIs carry paths). */
    fun sealUpload(uploadId: String, upload: LocalUpload): ByteArray = seal(
        CanonicalCbor.encode(
            LocalUploadCbor.serializer(),
            LocalUploadCbor(upload.source, upload.name, upload.mime, upload.folderId?.toByteArray()),
        ),
        UPLOAD_AD + uploadId.toByteArray(Charsets.UTF_8),
    )

    fun openUpload(uploadId: String, sealed: ByteArray): LocalUpload? {
        val cbor = open(sealed, UPLOAD_AD + uploadId.toByteArray(Charsets.UTF_8)) ?: return null
        val c = CanonicalCbor.decode(LocalUploadCbor.serializer(), cbor) ?: return null
        return validated { LocalUpload(c.source, c.name, c.mime, c.folderId?.let(Id::of)) }
    }

    /** Where an export writes to. */
    fun sealTarget(downloadId: String, target: String): ByteArray =
        seal(target.toByteArray(Charsets.UTF_8), TARGET_AD + downloadId.toByteArray(Charsets.UTF_8))

    fun openTarget(downloadId: String, sealed: ByteArray): String? =
        open(sealed, TARGET_AD + downloadId.toByteArray(Charsets.UTF_8))?.toString(Charsets.UTF_8)

    private fun seal(plain: ByteArray, ad: ByteArray): ByteArray {
        val nonce = sodium.randomBytes(CofferFormat.NONCE_BYTES)
        return nonce + localKey.withBytes { sodium.aeadEncrypt(plain, ad, nonce, it) }
    }

    private fun open(sealed: ByteArray, ad: ByteArray): ByteArray? {
        if (sealed.size < CofferFormat.NONCE_BYTES + CofferFormat.TAG_BYTES) return null
        val nonce = sealed.copyOfRange(0, CofferFormat.NONCE_BYTES)
        val body = sealed.copyOfRange(CofferFormat.NONCE_BYTES, sealed.size)
        return localKey.withBytes { sodium.aeadDecrypt(body, ad, nonce, it) }
    }

    private fun fileAd(fileId: Id) = FILE_AD + fileId.toByteArray()

    private companion object {
        val FILE_AD = "coffer/local/v1/file".toByteArray(Charsets.US_ASCII)
        val FOLDER_AD = "coffer/local/v1/folder".toByteArray(Charsets.US_ASCII)
        val UPLOAD_AD = "coffer/local/v1/upload".toByteArray(Charsets.US_ASCII)
        val TARGET_AD = "coffer/local/v1/target".toByteArray(Charsets.US_ASCII)
    }
}

/** A file the user asked to upload: a content URI, its display name and type, and the folder to put it in. */
data class LocalUpload(val source: String, val name: String, val mime: String?, val folderId: Id? = null)

/** A folder in the local index. [parentId] is null for the root, or may name a folder that is gone (an orphan). */
data class LocalFolder(val name: String, val parentId: Id?) {
    init {
        require(FileNames.isValid(name)) { "invalid name" }
    }
}

@Serializable
private class LocalUploadCbor(
    @CborLabel(1) val source: String,
    @CborLabel(2) val name: String,
    @CborLabel(3) val mime: String?,
    @CborLabel(4) val folderId: ByteArray?,
)

@Serializable
private class LocalFolderCbor(
    @CborLabel(1) val name: String,
    @CborLabel(2) val parentId: ByteArray?,
)

@Serializable
private class LocalFileMetaCbor(
    @CborLabel(1) val name: String,
    @CborLabel(2) val folderId: ByteArray?,
    @CborLabel(3) val mime: String,
    @CborLabel(4) val createdAt: Long,
    @CborLabel(5) val modifiedAt: Long,
    @CborLabel(6) val contentHash: ByteArray,
    @CborLabel(7) val wrappedDek: ByteArray,
)
