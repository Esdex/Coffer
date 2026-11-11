package me.esdex.coffer.core.sync

import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.FileKeys
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalSealer
import me.esdex.coffer.core.crypto.format.MetaCodec
import me.esdex.coffer.core.crypto.format.SnapshotCodec
import me.esdex.coffer.core.crypto.sodium.Sodium

/**
 * What sync needs from the unlocked vault. Borrowed, not owned: the vault closes the keys on lock,
 * and anything still holding this fails instead of using wiped keys. [writeVersion] is the caption
 * format written (SPEC §4.5); version 1 only stands in for an older client in tests.
 */
class VaultKeys(
    val sodium: Sodium,
    metaKey: SecretKey,
    indexKey: SecretKey,
    private val wrapKey: SecretKey,
    localKey: SecretKey,
    writeVersion: Int = Captions.V2,
) {
    val meta = MetaCodec(sodium, metaKey, writeVersion)
    val snapshots = SnapshotCodec(sodium, indexKey)
    val local = LocalSealer(sodium, localKey)

    fun wrapDek(dek: SecretKey, fileId: Id): Bytes = FileKeys.wrapDek(sodium, wrapKey, dek, fileId)

    fun unwrapDek(meta: FileMeta, fileId: Id): SecretKey = FileKeys.unwrapDek(sodium, wrapKey, meta.wrappedDek, fileId)
}

/**
 * The only secrets a running transfer holds (SPEC §6): the file's DEK, and its content hash to check
 * the result against. Kept in memory only; after the process dies a transfer waits for the next unlock.
 */
class TransferKeys(val dek: SecretKey, val contentHash: Bytes) : AutoCloseable {
    override fun close() = dek.close()
}
