package me.esdex.coffer.feature.browser.preview

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Base64Url
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Which files get a preview, by type: pictures (not SVG, which ImageDecoder does not draw), videos, music. */
enum class PreviewKind {
    PICTURE,
    VIDEO,
    AUDIO,
    ;

    companion object {
        fun of(mime: String): PreviewKind? = when {
            mime == "image/svg+xml" -> null
            mime.startsWith("image/") -> PICTURE
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("audio/") -> AUDIO
            else -> null
        }
    }
}

/**
 * Previews kept on the phone, one file each, named by the file id and sealed with this phone's local
 * key (see [me.esdex.coffer.core.crypto.format.LocalSealer.sealPreview]): a preview shows what the file
 * holds, so it is kept like the file's name. File ids are no secret; the index has them in the clear.
 */
class PreviewStore(private val dir: File) {

    fun read(fileId: Id): ByteArray? = runCatching { fileOf(fileId).takeIf { it.isFile }?.readBytes() }.getOrNull()

    fun write(fileId: Id, sealed: ByteArray) {
        dir.mkdirs()
        val target = fileOf(fileId)
        val temp = File(dir, target.name + ".tmp")
        temp.writeBytes(sealed)
        check(temp.renameTo(target)) { "could not store a preview" }
    }

    /** Deletes the previews of files no longer [known], and anything else that is not one. */
    fun retain(known: Set<Id>) {
        for (file in dir.listFiles().orEmpty()) {
            val id = Base64Url.decode(file.name)?.takeIf { it.size == 16 }?.let(Id::of)
            if (id == null || id !in known) file.delete()
        }
    }

    fun clear() {
        dir.deleteRecursively()
    }

    fun size(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    private fun fileOf(fileId: Id) = File(dir, Base64Url.encode(fileId.toByteArray()))
}

/** Seals and opens previews; null while the vault is locked, when the key is not there. */
interface PreviewSeal {
    fun seal(fileId: Id, picture: ByteArray): ByteArray

    /** Null if it does not open: damaged, or sealed by another vault. */
    fun open(fileId: Id, sealed: ByteArray): ByteArray?
}

/**
 * Where a file's preview comes from: the sealed copy on the phone, or else made anew, at most [parallel]
 * at a time and only once [idle] says no transfer is waiting for Telegram. The picture's bytes come
 * back; empty means the file has none to show (music without a cover), and that is kept too, so it is
 * not looked for again. Null means it could not be made now: locked, offline or interrupted; nothing is
 * kept, and the next time the file is on screen tries again.
 */
class PreviewLoader(
    private val store: PreviewStore,
    private val seal: () -> PreviewSeal?,
    private val idle: suspend () -> Unit,
    parallel: Int = 2,
) {
    private val gate = Semaphore(parallel)
    private val locks = ConcurrentHashMap<Id, Mutex>()

    suspend fun load(fileId: Id, make: suspend () -> ByteArray?): ByteArray? {
        // One at a time for a file: a second request finds what the first made.
        return locks.getOrPut(fileId, ::Mutex).withLock {
            val sealer = seal() ?: return@withLock null
            store.read(fileId)?.let { sealer.open(fileId, it) }?.let { return@withLock it }
            gate.withPermit {
                idle()
                val made = make() ?: return@withPermit null
                // Locked meanwhile: what was made is not kept, it would have to be kept in the clear.
                val now = seal() ?: return@withPermit null
                store.write(fileId, now.seal(fileId, made))
                made
            }
        }
    }
}
