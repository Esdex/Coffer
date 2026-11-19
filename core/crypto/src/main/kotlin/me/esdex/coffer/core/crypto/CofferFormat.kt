package me.esdex.coffer.core.crypto

import me.esdex.coffer.core.crypto.format.RecordKind

/** Sizes fixed by format v1 (SPEC §3-4). Changing any of them is a new format version. */
object CofferFormat {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16

    /** `nonce || ciphertext(key) || tag`, SPEC §3.2. */
    const val WRAPPED_KEY_BYTES = NONCE_BYTES + KEY_BYTES + TAG_BYTES

    const val ID_BYTES = 16
    const val SALT_BYTES = 16
    const val MASTER_ID_BYTES = 16
    const val CONTENT_HASH_BYTES = 32

    const val CHUNK_SIZE = 1_048_576
    const val ENCRYPTED_CHUNK_OVERHEAD = NONCE_BYTES + TAG_BYTES
    const val ENCRYPTED_CHUNK_SIZE = CHUNK_SIZE + ENCRYPTED_CHUNK_OVERHEAD
    const val CHUNKS_PER_PART = 512

    /** 1 PiB. Keeps part counts in Int range; a payload above it is invalid. */
    const val MAX_PLAIN_SIZE = 1L shl 50

    /**
     * Version 2 captions are this long inside the encryption, whatever they hold (SPEC §4.5): a two-byte
     * length, the CBOR, then zeros. Each is the worst case of its kind and a little more.
     */
    const val V2_BLOB_PAYLOAD = 600
    const val V2_OP_PAYLOAD = 352
    const val V2_INDEX_PAYLOAD = 64

    /**
     * A version 3 pack of operations is this long inside the encryption (SPEC §4.6): with the two-byte
     * length, 3000 bytes, a text of 4068 characters, under Telegram's 4096 for a message.
     */
    const val V3_OPS_PAYLOAD = 2998

    const val MAX_NAME_BYTES = 255
    const val MAX_MIME_BYTES = 127
    const val FALLBACK_MIME = "application/octet-stream"
}

/** Associated data strings, SPEC §3.2, §3.4, §4. Bytes, not text: they are hashed into tags. */
internal object AssociatedData {
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    val WRAP_MASTER = ascii("coffer/v1/wrap/master")
    private val WRAP_DEK = ascii("coffer/v1/wrap/dek")
    private val CHUNK = ascii("coffer/v1/chunk")
    val MASTER_ID_MESSAGE = ascii("coffer/v1/master-id")

    fun wrapDek(fileId: Id): ByteArray = WRAP_DEK + fileId.toByteArray()

    fun chunk(fileId: Id, chunkIndex: Long, isLast: Boolean): ByteArray {
        require(chunkIndex >= 0) { "chunk index must not be negative" }
        val out = ByteArray(CHUNK.size + CofferFormat.ID_BYTES + Long.SIZE_BYTES + 1)
        CHUNK.copyInto(out)
        fileId.toByteArray().copyInto(out, CHUNK.size)
        var offset = CHUNK.size + CofferFormat.ID_BYTES
        for (shift in 56 downTo 0 step 8) out[offset++] = (chunkIndex ushr shift).toByte()
        out[offset] = if (isLast) 1 else 0
        return out
    }

    fun meta(kind: RecordKind, version: Int): ByteArray = ascii("coffer/v$version/${kind.tag}")
}
