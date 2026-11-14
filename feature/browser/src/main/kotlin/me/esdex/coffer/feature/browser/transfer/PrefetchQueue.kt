package me.esdex.coffer.feature.browser.transfer

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.esdex.coffer.core.crypto.ContentLayout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Base64Url
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.storage.DeviceSealer
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.SealedTextFile

/**
 * Files waiting to be brought onto the phone ("Cache on this phone"), in order. Kept in a file, so the
 * work goes on after a restart, locked or not: it needs no key. File ids only, which Telegram sees anyway
 * and the cache's ledger holds too.
 */
class PrefetchQueue(
    file: File,
    sealer: DeviceSealer = DeviceSealer.NONE,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    // Sealed: which files the user keeps on the phone is not for a copy of its storage to show (audit 1, L14).
    private val file = SealedTextFile(file, sealer, "cache-queue")

    data class Item(val id: String, val fileId: Id)

    private val state = MutableStateFlow(read())
    val items: StateFlow<List<Item>> = state.asStateFlow()

    /** Queues those of [fileIds] not queued already, and returns what it queued. */
    @Synchronized
    fun add(fileIds: Collection<Id>): List<Item> {
        val queued = state.value.mapTo(HashSet()) { it.fileId }
        val added = fileIds.distinct().filter { it !in queued }.map { Item(newId(), it) }
        if (added.isNotEmpty()) save(state.value + added)
        return added
    }

    fun find(id: String): Item? = state.value.find { it.id == id }

    @Synchronized
    fun remove(id: String) {
        if (state.value.any { it.id == id }) save(state.value.filterNot { it.id == id })
    }

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(items: List<Item>) {
        file.write(items.joinToString("") { "${it.id} ${Base64Url.encode(it.fileId.toByteArray())}\n" })
        state.value = items
    }

    // A line that does not read is dropped: the worst that comes of it is a file not cached.
    private fun read(): List<Item> {
        val text = file.read() ?: return emptyList()
        return text.lines().mapNotNull { line ->
            val fields = line.split(' ')
            if (fields.size != 2 || fields[0].isEmpty()) return@mapNotNull null
            val fileId = Base64Url.decode(fields[1])?.takeIf { it.size == 16 }?.let(Id::of) ?: return@mapNotNull null
            Item(fields[0], fileId)
        }
    }
}

/** What Telegram holds of this file: the parts as sent, a little more than the file itself. */
fun IndexedFile.encryptedSize(): Long = (0 until partCount).sumOf { ContentLayout.encryptedPartSize(contentSize, it) }

/** Bytes still to download for every part to be on the phone whole, by the cache's account of what is ([held]). */
fun IndexedFile.missingBytes(held: Map<MessageId, Long>): Long = (0 until partCount).sumOf { part ->
    val size = ContentLayout.encryptedPartSize(contentSize, part)
    val have = parts[part]?.let(held::get) ?: 0L
    if (have >= size) 0L else size
}

/** Whole on the phone: it opens at once, and with no network. A part not in the index counts as missing. */
fun IndexedFile.isCached(held: Map<MessageId, Long>): Boolean = status == FileStatus.READY && missingBytes(held) == 0L
