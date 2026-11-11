package me.esdex.coffer.feature.browser.transfer

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.ui.theme.CachePrefs

/**
 * What "Cache on this phone" would take, shown before it starts: how much, whether the cache holds it
 * all, and whether the phone has the room.
 */
data class CacheOffer(
    /** Every whole file picked, in order; those already cached only have their place in line renewed. */
    val fileIds: List<Id>,
    /** Their size as Telegram holds them, which is what the cache counts. */
    val totalBytes: Long,
    /** What is not on the phone yet. */
    val missingBytes: Long,
    val freeBytes: Long,
    val prefs: CachePrefs,
) {
    val alreadyCached: Boolean get() = missingBytes == 0L

    /** Within the limit: nothing picked pushes out another picked file. */
    val fits: Boolean get() = totalBytes <= prefs.limitBytes

    /** The smallest limit on offer that would hold it all, when the present one does not. */
    val raiseTo: Int? get() = if (fits) null else CachePrefs.LIMITS_MB.firstOrNull { it.toLong() * MIB >= totalBytes }

    /** Room for what is to come, with some to spare for TDLib's own bookkeeping. */
    val enoughSpace: Boolean get() = missingBytes + SPACE_MARGIN <= freeBytes

    companion object {
        private const val MIB = 1024L * 1024
        const val SPACE_MARGIN = 64 * MIB

        /** Of [files], those whole in Saved Messages (ready: every part is there), with the cache's account of what it [held]. */
        fun of(files: List<IndexedFile>, held: Map<MessageId, Long>, freeBytes: Long, prefs: CachePrefs): CacheOffer {
            val whole = files.filter { it.status == FileStatus.READY }
            return CacheOffer(
                fileIds = whole.map { it.fileId },
                totalBytes = whole.sumOf { it.encryptedSize() },
                missingBytes = whole.sumOf { it.missingBytes(held) },
                freeBytes = freeBytes,
                prefs = prefs,
            )
        }
    }
}
