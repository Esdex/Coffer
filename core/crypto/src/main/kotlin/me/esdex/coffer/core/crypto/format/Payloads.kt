package me.esdex.coffer.core.crypto.format

import me.esdex.coffer.core.crypto.Argon2Params
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.Padme

// The model classes validate in init, so a decoded payload that breaks the format never exists as an object.

/** Vault header, SPEC §4.1. Plaintext: it is needed before the password is known. */
data class VaultHeader(
    val createdAt: Long,
    val salt: Bytes,
    val argon: Argon2Params,
    val wrappedMaster: Bytes,
    val masterId: Bytes,
) {
    init {
        require(salt.size == CofferFormat.SALT_BYTES) { "salt size" }
        require(wrappedMaster.size == CofferFormat.WRAPPED_KEY_BYTES) { "wrapped master size" }
        require(masterId.size == CofferFormat.MASTER_ID_BYTES) { "master id size" }
    }

    companion object {
        const val FORMAT = 1
    }
}

/** Metadata carried only by part 0, SPEC §4.2. */
data class FileMeta(
    val name: String,
    /** null = root. */
    val folderId: Id?,
    val mime: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val contentHash: Bytes,
    val wrappedDek: Bytes,
) {
    init {
        require(FileNames.isValid(name)) { "invalid name" }
        require(MimeTypes.isNormalized(mime)) { "mime not normalized" }
        require(contentHash.size == CofferFormat.CONTENT_HASH_BYTES) { "content hash size" }
        require(wrappedDek.size == CofferFormat.WRAPPED_KEY_BYTES) { "wrapped DEK size" }
    }
}

/**
 * Caption payload of one file part, SPEC §4.2. [meta] is present exactly on part 0. A [padded] file's
 * content runs on in zeros to its padded size (§4.5), and its parts are counted from that.
 */
data class BlobPayload(
    val fileId: Id,
    val partIndex: Int,
    val partCount: Int,
    val plainSize: Long,
    val meta: FileMeta?,
    val padded: Boolean = false,
) {
    val contentSize: Long get() = Padme.contentSize(plainSize, padded)

    init {
        require(plainSize in 0..CofferFormat.MAX_PLAIN_SIZE) { "size out of range" }
        require(partCount == ContentLayout.partCount(contentSize)) { "part count does not match size" }
        require(partIndex in 0 until partCount) { "part index out of range" }
        require((partIndex == 0) == (meta != null)) { "metadata belongs to part 0 only" }
    }
}

/** Journal operations, SPEC §4.3. */
sealed interface Op {
    val opId: Id
    val ts: Long
    val type: String

    data class FolderCreate(override val opId: Id, override val ts: Long, val folderId: Id, val parentId: Id?, val name: String) : Op {
        override val type get() = "folder.create"
        init { require(FileNames.isValid(name)) { "invalid name" } }
    }

    data class FolderRename(override val opId: Id, override val ts: Long, val folderId: Id, val name: String) : Op {
        override val type get() = "folder.rename"
        init { require(FileNames.isValid(name)) { "invalid name" } }
    }

    data class FolderMove(override val opId: Id, override val ts: Long, val folderId: Id, val parentId: Id?) : Op {
        override val type get() = "folder.move"
    }

    data class FolderDelete(override val opId: Id, override val ts: Long, val folderId: Id) : Op {
        override val type get() = "folder.delete"
    }

    data class FileRename(override val opId: Id, override val ts: Long, val fileId: Id, val name: String) : Op {
        override val type get() = "file.rename"
        init { require(FileNames.isValid(name)) { "invalid name" } }
    }

    data class FileMove(override val opId: Id, override val ts: Long, val fileId: Id, val folderId: Id?) : Op {
        override val type get() = "file.move"
    }

    data class FileDelete(override val opId: Id, override val ts: Long, val fileId: Id) : Op {
        override val type get() = "file.delete"
    }
}

/** Caption payload of an index snapshot, SPEC §4.4. A version 2 snapshot is [padded] (§4.5). */
data class IndexPayload(
    val snapshotId: Id,
    val lastAppliedMessageId: Long,
    val createdAt: Long,
    val plainSize: Long,
    val padded: Boolean = false,
) {
    init {
        require(lastAppliedMessageId >= 0) { "negative message id" }
        require(plainSize >= 0) { "negative size" }
    }
}
