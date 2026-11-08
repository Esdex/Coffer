package me.esdex.coffer.core.sync

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.KeyRing
import me.esdex.coffer.core.crypto.LocalKeys
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.format.BlobPayload
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalUpload
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.InMemoryVaultIndex
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.Progress
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StoredMessage
import me.esdex.coffer.core.storage.StorageException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

internal val sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))

internal fun keysOf(master: SecretKey): VaultKeys {
    val ring = KeyRing.derive(sodium, master)
    return VaultKeys(sodium, ring.metaKey, ring.indexKey, ring.wrapKey, LocalKeys.derive(sodium, master))
}

internal class BytesSource(private val bytes: ByteArray) : UploadSource {
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/** A large source generated on the fly, so a multi-part file needs no 512 MiB array of its own. */
internal class PatternSource(private val size: Long) : UploadSource {
    override fun open(): InputStream = object : InputStream() {
        var position = 0L
        override fun read(): Int = if (position >= size) -1 else ((position++ % 251).toInt())
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            val n = minOf(len.toLong(), size - position).toInt()
            for (i in 0 until n) b[off + i] = ((position + i) % 251).toByte()
            position += n
            return n
        }
    }
}

/** Independent of the code under test: SHA-256 from the JDK. */
internal fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal class HashingSink : OutputStream() {
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    override fun write(b: Int) = digest.update(b.toByte())
    override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
    fun hex() = digest.digest().joinToString("") { "%02x".format(it) }
}

/** Counts what a device reads, to tell a snapshot load from a full pass over the history. */
internal class CountingStorage(private val inner: StorageBackend) : StorageBackend by inner {
    @Volatile var messagesRead = 0
    @Volatile var blobsOpened = 0

    /** Runs before each blob is opened: lets a test change the cloud at exactly that moment. */
    @Volatile var beforeOpen: suspend (MessageId) -> Unit = {}

    override suspend fun historyBefore(before: MessageId?, limit: Int): List<StoredMessage> =
        inner.historyBefore(before, limit).also { messagesRead += it.size }

    override suspend fun historyAfter(after: MessageId, limit: Int): List<StoredMessage> =
        inner.historyAfter(after, limit).also { messagesRead += it.size }

    override suspend fun openBlob(id: MessageId, offset: Long, length: Long, onProgress: (Progress) -> Unit): InputStream {
        beforeOpen(id)
        return inner.openBlob(id, offset, length, onProgress).also { blobsOpened++ }
    }
}

/** One device: its storage handle, its own index, the shared vault keys. */
internal class Device(private val cloud: FakeCloud, val keys: VaultKeys, temp: java.io.File) {
    val storage = CountingStorage(cloud.device())
    val index = InMemoryVaultIndex()
    val indexer = Indexer(storage, index, keys)
    val snapshotter = Snapshotter(storage, index, indexer, keys, java.io.File(temp, "snapshots")) { 1_762_531_200_000 }

    // On the cloud's clock, so message dates and "now" agree when a test moves time on.
    val maintenance = Maintenance(storage, index, indexer, snapshotter, keys) { cloud.now() * 1000 }
    val preparer = UploadPreparer(keys, index) { 1_762_531_200_000 }
    val runner = UploadRunner(sodium, storage, index, temp)
    val downloader = DownloadRunner(sodium, storage, index)
    val deleter = FileDeleter(storage, index)
    val ops = VaultOps(storage, keys) { 1_762_531_200_000 }

    fun queued(name: String, mime: String? = null, folderId: Id? = null): Pair<PendingUpload, LocalUpload> {
        val id = Random.nextLong().toString()
        val request = LocalUpload("content://test/$name", name, mime, folderId)
        return PendingUpload(id, keys.local.sealUpload(id, request), null, emptyList(), null) to request
    }

    suspend fun prepare(name: String, source: UploadSource, mime: String? = null, folderId: Id? = null): UploadPreparer.Prepared {
        val (queued, request) = queued(name, mime, folderId)
        return preparer.prepare(queued, request, source)
    }

    suspend fun upload(name: String, source: UploadSource, mime: String? = "application/octet-stream", folderId: Id? = null): Id {
        val prepared = prepare(name, source, mime, folderId)
        runner.run(prepared.upload, prepared.keys, source)
        return prepared.upload.fileId!!
    }

    suspend fun tree(): VaultTree = VaultTree.of(index, keys)

    suspend fun sendOp(op: Op) = storage.sendText(keys.meta.encodeOp(op))

    /**
     * What the user sees, by name: each folder with the folder it shows in, each file with its folder and
     * status. Names in these tests are unique, so names identify objects.
     */
    suspend fun view(): View {
        val tree = tree()
        fun nameOf(id: Id?) = id?.let { tree.folders.getValue(it).name }
        return View(
            folders = tree.folders.map { (id, folder) -> folder.name to nameOf(tree.parentOf(id)) }.toSet(),
            files = tree.files.map { (id, meta) -> Triple(meta.name, nameOf(tree.folderOf(id)), index.file(id)!!.status) }.toSet(),
        )
    }

    fun meta(file: IndexedFile): FileMeta = keys.local.openFileMeta(file.fileId, file.sealedMeta)!!

    suspend fun download(fileId: Id, output: OutputStream) {
        val file = index.file(fileId)!!
        downloader.run(fileId, keys.transferKeys(fileId, file.sealedMeta)!!, output)
    }
}

internal data class View(val folders: Set<Pair<String, String?>>, val files: Set<Triple<String, String?, FileStatus>>)

/** Runs the device's indexer live in the background for [block]. */
internal fun <T> following(device: Device, block: suspend () -> T): T = runBlocking {
    val job: Job = CoroutineScope(Dispatchers.Default).launch { device.indexer.run() }
    try {
        block()
    } finally {
        job.cancel()
    }
}

internal suspend fun Device.awaitFiles(timeout: Long = 10_000, predicate: (List<IndexedFile>) -> Boolean) =
    withTimeout(timeout) { index.observeFiles().first(predicate) }
