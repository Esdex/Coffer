package me.esdex.coffer.feature.browser.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Base64Url
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.ui.theme.CachePrefs
import java.io.File

/**
 * The file cache: the encrypted pieces of files already read stay on the phone, so opening them again
 * costs neither time nor traffic. They are what Telegram holds, so the cache is no use without the
 * vault's keys, and the lock leaves it alone unless asked to clear it.
 *
 * What is kept, how big and when it was last read is written down in [ledger]: TDLib's own record of
 * file access is the file system's, which Android often does not keep. Over [CachePrefs.limitBytes]
 * the pieces read longest ago go first; never those of a file still open ([inUse]).
 */
class BlobCache(
    private val scope: CoroutineScope,
    private val ledger: File,
    private val prefs: () -> CachePrefs,
    private val inUse: (Id) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val fileId: Id, val bytes: Long, val used: Long)

    private val lock = Mutex()
    private var entries: MutableMap<MessageId, Entry>? = null

    private val heldFlow = MutableStateFlow<Map<MessageId, Long>>(emptyMap())

    /** Bytes of each piece the cache holds, as the ledger has it; what the browser's check marks go by. */
    val held: StateFlow<Map<MessageId, Long>> = heldFlow.asStateFlow()

    init {
        scope.launch { lock.withLock { heldFlow.value = load().mapValues { it.value.bytes } } }
    }

    /**
     * [parts] of [fileId] were read and are done with: kept and marked as just used, then the cache is
     * brought back under its limit. With the cache off the limit is nothing, so they go at once, as
     * before there was a cache; unless the file was opened again meanwhile, then with its next close.
     */
    fun keep(fileId: Id, parts: Collection<MessageId>, storage: StorageBackend) {
        if (parts.isEmpty()) return
        scope.launch {
            lock.withLock {
                val all = load()
                val now = clock()
                for (part in parts) {
                    val bytes = runCatching { storage.localBytes(part) }.getOrDefault(0L)
                    if (bytes > 0) all[part] = Entry(fileId, bytes, now) else all.remove(part)
                }
                trimLocked(all, storage)
                save(all)
            }
        }
    }

    /** What the cache holds by its own account, in bytes. */
    suspend fun size(): Long = lock.withLock { load().values.sumOf { it.bytes } }

    /** Back under the limit, after it was lowered. */
    suspend fun trim(storage: StorageBackend) = lock.withLock {
        val all = load()
        trimLocked(all, storage)
        save(all)
    }

    /**
     * Everything goes but what an open file is reading; that goes when the file closes. Then, with nothing
     * being read or saved ([idle]), [purge] drops what the ledger never heard of: pieces left half
     * downloaded by a process killed mid-read, or from before there was a cache.
     */
    suspend fun clear(storage: StorageBackend, idle: suspend () -> Boolean = { false }, purge: suspend () -> Unit = {}) = lock.withLock {
        val all = load()
        for ((part, entry) in all.entries.toList()) {
            if (inUse(entry.fileId)) continue
            runCatching { storage.releaseBlob(part) }
            all.remove(part)
        }
        save(all)
        if (all.isEmpty() && idle()) runCatching { purge() }
    }

    /**
     * Crosses out pieces not [known] to the index whose message is [gone]. A file deleted, here or on
     * another device, has its pieces deleted from the phone by TDLib along with their messages; still in
     * the ledger they would take up room under the limit, and push out files that are there, until their
     * turn to go came. Not being in the index is not enough: a rescan empties it before reading the
     * history again. A piece whose message cannot be asked about now stays.
     */
    suspend fun retain(known: Set<MessageId>, gone: suspend (MessageId) -> Boolean) = lock.withLock {
        val all = load()
        val strays = all.keys.filter { it !in known }.filter { runCatching { gone(it) }.getOrDefault(false) }
        if (strays.isEmpty()) return@withLock
        all.keys.removeAll(strays.toSet())
        save(all)
    }

    /** Signed out: TDLib has deleted its files itself, and the list of them goes too. */
    suspend fun forget() = lock.withLock {
        entries = HashMap()
        ledger.delete()
        heldFlow.value = emptyMap()
    }

    private suspend fun trimLocked(all: MutableMap<MessageId, Entry>, storage: StorageBackend) {
        val limit = prefs().let { if (it.enabled) it.limitBytes else 0L }
        var total = all.values.sumOf { it.bytes }
        if (total <= limit) return
        for ((part, entry) in all.entries.sortedBy { it.value.used }) {
            if (total <= limit) break
            if (inUse(entry.fileId)) continue
            runCatching { storage.releaseBlob(part) }
            all.remove(part)
            total -= entry.bytes
        }
    }

    private fun load(): MutableMap<MessageId, Entry> = entries ?: read().also { entries = it }

    // One line a piece: message id, file id, bytes, last used. A line that does not read is dropped:
    // the worst that comes of it is a piece kept until the next clear.
    private fun read(): MutableMap<MessageId, Entry> {
        val all = HashMap<MessageId, Entry>()
        if (!ledger.exists()) return all
        ledger.forEachLine { line ->
            val fields = line.split(' ')
            if (fields.size != 4) return@forEachLine
            val part = fields[0].toLongOrNull() ?: return@forEachLine
            val fileId = Base64Url.decode(fields[1])?.takeIf { it.size == 16 }?.let(Id::of) ?: return@forEachLine
            val bytes = fields[2].toLongOrNull() ?: return@forEachLine
            val used = fields[3].toLongOrNull() ?: return@forEachLine
            all[MessageId(part)] = Entry(fileId, bytes, used)
        }
        return all
    }

    private fun save(all: Map<MessageId, Entry>) {
        ledger.parentFile?.mkdirs()
        val temp = File(ledger.path + ".tmp")
        temp.bufferedWriter().use { out ->
            for ((part, e) in all) out.write("${part.value} ${Base64Url.encode(e.fileId.toByteArray())} ${e.bytes} ${e.used}\n")
        }
        check(temp.renameTo(ledger)) { "could not store ${ledger.name}" }
        heldFlow.value = all.mapValues { it.value.bytes }
    }
}
