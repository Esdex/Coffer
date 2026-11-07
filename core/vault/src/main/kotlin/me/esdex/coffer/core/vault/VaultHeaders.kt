package me.esdex.coffer.core.vault

import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend

/** Finding and publishing vault headers in Saved Messages (SPEC §4.1). */
object VaultHeaders {

    /**
     * Headers newest first. Pinned messages first; a full pass over the history only if none of them is a
     * header. Pinning is a hint for speed, not part of the format, so an unpinned header still counts.
     *
     * @param onScanned messages looked at so far in the full pass, for progress.
     */
    suspend fun find(storage: StorageBackend, onScanned: (Int) -> Unit = {}): List<FoundHeader> {
        val pinned = storage.pinnedMessages().mapNotNull(FoundHeader::from)
        if (pinned.isNotEmpty()) return pinned.sortedByDescending { it.messageId }

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
