package me.esdex.coffer.core.vault

import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend

/** Finding and publishing vault headers in Saved Messages (SPEC §4.1). */
object VaultHeaders {

    /**
     * Headers newest first. Pinned messages first; a full pass over the history only if none of them is a
     * header, or none is of the [known] vault. Pinning is a hint for speed, not part of the format, so an
     * unpinned header still counts, and anyone in the Telegram account can pin a header of their own
     * over it (audit 1, M2).
     *
     * @param known the master_id of the vault this device knows, if any.
     * @param onScanned messages looked at so far in the full pass, for progress.
     */
    suspend fun find(storage: StorageBackend, known: Bytes? = null, onScanned: (Int) -> Unit = {}): List<FoundHeader> {
        val pinned = storage.pinnedMessages().mapNotNull(FoundHeader::from)
        if (pinned.isNotEmpty() && (known == null || pinned.any { it.header.masterId == known })) {
            return pinned.sortedByDescending { it.messageId }
        }
        return findAll(storage, onScanned)
    }

    /** Every header in Saved Messages, newest first, by a full pass over the history. */
    suspend fun findAll(storage: StorageBackend, onScanned: (Int) -> Unit = {}): List<FoundHeader> {
        val found = mutableListOf<FoundHeader>()
        var scanned = 0
        var before: MessageId? = null
        while (true) {
            val page = storage.historyBefore(before, StorageBackend.MAX_PAGE)
            if (page.isEmpty()) break
            page.mapNotNullTo(found, FoundHeader::from)
            scanned += page.size
            onScanned(scanned)
            before = page.last().id
        }
        return found.sortedByDescending { it.messageId }
    }

    /**
     * Sends [vault]'s header and pins it. Refuses if a header appeared in the meantime, for example
     * created on another device while this one was showing the recovery key.
     */
    suspend fun publish(storage: StorageBackend, vault: NewVault): FoundHeader {
        if (storage.pinnedMessages().any { FoundHeader.from(it) != null }) throw VaultExistsException()
        val sent = storage.sendText(VaultHeaderCodec.encode(vault.header))
        storage.pin(sent.id)
        return FoundHeader(sent.id, vault.header)
    }
}

class VaultExistsException : Exception("a vault header already exists in Saved Messages")
