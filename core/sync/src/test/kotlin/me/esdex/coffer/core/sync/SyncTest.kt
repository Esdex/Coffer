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
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.InMemoryVaultIndex
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.FakeStorageBackend
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.StorageException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

private val sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))

private fun keysOf(master: SecretKey): VaultKeys {
    val ring = KeyRing.derive(sodium, master)
    return VaultKeys(sodium, ring.metaKey, ring.wrapKey, LocalKeys.derive(sodium, master))
}

private class BytesSource(private val bytes: ByteArray) : UploadSource {
    override fun open(): InputStream = ByteArrayInputStream(bytes)
}

/** A large source generated on the fly, so a multi-part file needs no 512 MiB array of its own. */
private class PatternSource(private val size: Long) : UploadSource {
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
private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private class HashingSink : OutputStream() {
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    override fun write(b: Int) = digest.update(b.toByte())
    override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
    fun hex() = digest.digest().joinToString("") { "%02x".format(it) }
}

/** One device: its storage handle, its own index, the shared vault keys. */
private class Device(cloud: FakeCloud, val keys: VaultKeys, temp: java.io.File) {
    val storage: FakeStorageBackend = cloud.device()
    val index = InMemoryVaultIndex()
    val indexer = Indexer(storage, index, keys)
    val preparer = UploadPreparer(keys, index) { 1_762_531_200_000 }
    val runner = UploadRunner(sodium, storage, index, temp)
    val downloader = DownloadRunner(sodium, storage, index)
    val deleter = FileDeleter(storage, index)

    fun queued(name: String, mime: String? = null): Pair<PendingUpload, LocalUpload> {
        val id = Random.nextLong().toString()
        val request = LocalUpload("content://test/$name", name, mime)
        return PendingUpload(id, keys.local.sealUpload(id, request), null, emptyList(), null) to request
    }

    suspend fun prepare(name: String, source: UploadSource, mime: String? = null): UploadPreparer.Prepared {
        val (queued, request) = queued(name, mime)
        return preparer.prepare(queued, request, source)
    }

    suspend fun upload(name: String, source: UploadSource, mime: String? = "application/octet-stream"): Id {
        val prepared = prepare(name, source, mime)
        runner.run(prepared.upload, prepared.keys, source)
        return prepared.upload.fileId!!
    }

    fun meta(file: IndexedFile): FileMeta = keys.local.openFileMeta(file.fileId, file.sealedMeta)!!

    suspend fun download(fileId: Id, output: OutputStream) {
        val file = index.file(fileId)!!
        downloader.run(fileId, keys.transferKeys(fileId, file.sealedMeta)!!, output)
    }
}

class SyncTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud(pageCap = 5)

    private fun device() = Device(cloud, keysOf(master), temp.newFolder())

    /** Runs the device's indexer live in the background for [block]. */
    private fun <T> following(device: Device, block: suspend () -> T): T = runBlocking {
        val job: Job = CoroutineScope(Dispatchers.Default).launch { device.indexer.run() }
        try {
            block()
        } finally {
            job.cancel()
        }
    }

    private suspend fun Device.awaitFiles(timeout: Long = 10_000, predicate: (List<IndexedFile>) -> Boolean) =
        withTimeout(timeout) { index.observeFiles().first(predicate) }

    @Test
    fun filesRoundTripToAnotherDevice() = runBlocking {
        val here = device()
        val there = device()
        val sizes = listOf(0, 1, 1_048_576, 1_048_577, 3_000_123)
        val sources = sizes.associateWith { Random(it).nextBytes(it) }
        val ids = sizes.associateWith { here.upload("file-$it.bin", BytesSource(sources.getValue(it)), "x/y") }

        there.indexer.catchUp()
        for (size in sizes) {
            val file = there.index.file(ids.getValue(size))!!
            assertEquals(FileStatus.READY, file.status)
            assertEquals(size.toLong(), file.plainSize)
            val meta = there.meta(file)
            assertEquals("file-$size.bin", meta.name)
            assertEquals("x/y", meta.mime)
            assertNull(meta.folderId)
            val out = ByteArrayOutputStream()
            there.download(file.fileId, out)
            assertArrayEquals(sources.getValue(size), out.toByteArray())
        }
        assertTrue(here.index.uploads().isEmpty())
        assertEquals(sizes.map { FileStatus.READY }, sizes.map { here.index.file(ids.getValue(it))!!.status })
    }

    @Test
    fun multiPartUploadResumesAfterAnInterruption() = runBlocking {
        val size = 512L * 1024 * 1024 + 5
        val source = PatternSource(size)
        val here = device()
        var transfers = 0
        cloud.onTransfer = { if (++transfers == 2) throw StorageException("connection lost") }

        val prepared = here.prepare("big.bin", source)
        assertEquals(2, prepared.upload.captions.size)
        try {
            here.runner.run(prepared.upload, prepared.keys, source)
            fail("the second part should have failed")
        } catch (_: StorageException) {
        }
        val fileId = prepared.upload.fileId!!
        assertEquals(setOf(0), here.index.parts(fileId).keys)
        assertEquals(FileStatus.UPLOADING, here.index.file(fileId)!!.status)
        // Another device sees part 0 without part 1: incomplete, not ready.
        val watcher = device()
        watcher.indexer.catchUp()
        assertEquals(FileStatus.INCOMPLETE, watcher.index.file(fileId)!!.status)

        // Resuming sends only the missing part.
        here.runner.run(here.index.uploads().single(), prepared.keys, source)
        assertEquals(3, transfers)
        assertEquals(FileStatus.READY, here.index.file(fileId)!!.status)

        val there = device()
        there.indexer.catchUp()
        val sink = HashingSink()
        there.download(fileId, sink)
        assertEquals(sha256(source.open()), sink.hex())
        watcher.indexer.catchUp()
        assertEquals(FileStatus.READY, watcher.index.file(fileId)!!.status)
    }

    @Test
    fun changedSourceIsAbandonedNotPublished() = runBlocking {
        val here = device()
        val original = Random(1).nextBytes(2_000_000)
        val prepared = here.prepare("a.bin", BytesSource(original))
        val changed = original.copyOf().also { it[1_500_000] = (it[1_500_000] + 1).toByte() }
        try {
            here.runner.run(prepared.upload, prepared.keys, BytesSource(changed))
            fail("expected SourceChangedException")
        } catch (_: SourceChangedException) {
        }
        val fileId = prepared.upload.fileId!!
        assertNull(here.index.file(fileId))
        assertTrue(here.index.uploads().isEmpty())
        // Only the file.delete operation is left in Saved Messages.
        assertEquals(listOf(MessageContent.Text::class), cloud.allMessages().map { it.content::class })

        val there = device()
        there.indexer.catchUp()
        assertTrue(there.index.files().isEmpty())
    }

    @Test
    fun longerSourceIsAbandonedToo() = runBlocking {
        val here = device()
        val prepared = here.prepare("a.bin", BytesSource(ByteArray(1000)))
        try {
            here.runner.run(prepared.upload, prepared.keys, BytesSource(ByteArray(1001)))
            fail("expected SourceChangedException")
        } catch (_: SourceChangedException) {
        }
        assertNull(here.index.file(prepared.upload.fileId!!))
    }

    @Test
    fun deleteReachesOtherDevicesAsDeletionNotDamage() {
        val here = device()
        val there = device()
        following(there) {
            val fileId = here.upload("doomed.txt", BytesSource("bye".toByteArray()))
            there.awaitFiles { files -> files.any { it.fileId == fileId && it.status == FileStatus.READY } }
            here.deleter.delete(here.keys, fileId, now = 2)
            there.awaitFiles { files -> files.none { it.fileId == fileId } }
            assertTrue(there.index.isDeleted(fileId))
        }
        assertTrue(cloud.allMessages().none { it.content is MessageContent.Blob })
    }

    @Test
    fun partDeletedOutsideTheAppMarksTheFileDamaged() {
        val here = device()
        val there = device()
        following(there) {
            val fileId = here.upload("precious.txt", BytesSource("keep me".toByteArray()))
            there.awaitFiles { files -> files.any { it.fileId == fileId && it.status == FileStatus.READY } }
            val part = here.index.parts(fileId).getValue(0)
            cloud.deleteFromOutside(listOf(part))
            there.awaitFiles { files -> files.any { it.fileId == fileId && it.status == FileStatus.DAMAGED } }
        }
    }

    @Test
    fun foreignAndOtherVaultsMessagesAreIgnored() = runBlocking {
        val here = device()
        here.storage.sendText("just a note")
        cloud.postFromOutside(MessageContent.Other)
        here.storage.sendText("coffer:v1:op:not-base64!")
        // A valid caption, but of another vault.
        val stranger = keysOf(MasterKeys.generate(sodium))
        val strangerCaption = stranger.meta.encodeBlob(BlobPayload(Id.random(sodium), 1, 2, 600_000_000, null))
        here.storage.sendBlob(temp.newFile("cfr_000000000000.bin").apply { writeBytes(ByteArray(10)) }, strangerCaption)
        val fileId = here.upload("mine.txt", BytesSource("mine".toByteArray()))

        val there = device()
        there.indexer.catchUp()
        assertEquals(listOf(fileId), there.index.files().map { it.fileId })
        assertEquals(cloud.allMessages().last().id, there.index.lastApplied())
    }

    @Test
    fun catchingUpFromScratchMatchesFollowingLive() {
        val here = device()
        val live = device()
        following(live) {
            val keep = here.upload("keep.bin", BytesSource(ByteArray(10) { 1 }))
            val gone = here.upload("gone.bin", BytesSource(ByteArray(10) { 2 }))
            here.deleter.delete(here.keys, gone, now = 3)
            here.upload("also.bin", BytesSource(ByteArray(10) { 3 }))
            live.awaitFiles { files -> files.size == 2 && files.all { it.status == FileStatus.READY } && files.any { it.fileId == keep } }
        }
        val fresh = device()
        runBlocking { fresh.indexer.catchUp() }
        fun summary(device: Device) = runBlocking {
            device.index.files().map { Triple(it.fileId, it.status, device.meta(it).name) }.sortedBy { it.third }
        }
        assertEquals(summary(live), summary(fresh))
    }

    @Test
    fun catchUpContinuesFromTheLastAppliedMessage() = runBlocking {
        val here = device()
        val there = device()
        here.upload("one.bin", BytesSource(ByteArray(5)))
        there.indexer.catchUp()
        val two = here.upload("two.bin", BytesSource(ByteArray(6)))
        there.indexer.catchUp()
        assertEquals(FileStatus.READY, there.index.file(two)!!.status)
        assertEquals(2, there.index.files().size)
    }

    @Test
    fun cancelledUploadLeavesNothingBehind() = runBlocking {
        val size = 512L * 1024 * 1024 + 1
        val source = PatternSource(size)
        val here = device()
        var transfers = 0
        cloud.onTransfer = { if (++transfers == 2) throw StorageException("stop") }
        val prepared = here.prepare("x.bin", source)
        runCatching { here.runner.run(prepared.upload, prepared.keys, source) }
        here.runner.cancel(here.index.uploads().single())
        assertNull(here.index.file(prepared.upload.fileId!!))
        assertTrue(here.index.uploads().isEmpty())
        assertTrue(cloud.allMessages().none { it.content is MessageContent.Blob })
    }

    @Test
    fun notEnoughSpaceStopsBeforeSending() = runBlocking {
        val here = device()
        val tight = UploadRunner(sodium, here.storage, here.index, temp.newFolder()) { 1024 }
        val prepared = here.prepare("x.bin", BytesSource(ByteArray(100)))
        try {
            tight.run(prepared.upload, prepared.keys, BytesSource(ByteArray(100)))
            fail("expected NotEnoughSpaceException")
        } catch (_: NotEnoughSpaceException) {
        }
        assertTrue(cloud.allMessages().isEmpty())
    }

    @Test
    fun tempDirectoryThatDoesNotExistYetWorks() = runBlocking {
        // File.usableSpace is 0 for a path that does not exist: the space check must not see that.
        val here = device()
        val fresh = java.io.File(temp.root, "not-created-yet/upload")
        val runner = UploadRunner(sodium, here.storage, here.index, fresh)
        val prepared = here.prepare("x.bin", BytesSource(ByteArray(100)))
        runner.run(prepared.upload, prepared.keys, BytesSource(ByteArray(100)))
        assertEquals(FileStatus.READY, here.index.file(prepared.upload.fileId!!)!!.status)
    }

    @Test
    fun uploaderFinishingWhileTheIndexerAppliesItsPartIsNotUndone() = runBlocking {
        // The indexer reads the file row, the uploader marks it READY, the indexer writes: the
        // indexer's write must not bring back UPLOADING.
        val here = device()
        val prepared = here.prepare("race.bin", BytesSource(ByteArray(100)))
        val fileId = prepared.upload.fileId!!
        val racing = object : VaultIndex by here.index {
            var armed = true
            override suspend fun file(fileId: Id) = here.index.file(fileId).also {
                if (armed && it?.status == FileStatus.UPLOADING) {
                    armed = false
                    here.index.setStatus(fileId, FileStatus.READY)
                }
            }
        }
        here.runner.run(prepared.upload, prepared.keys, BytesSource(ByteArray(100)))
        // Back to the moment the race happens: still uploading, upload still recorded.
        here.index.setStatus(fileId, FileStatus.UPLOADING)
        here.index.putUpload(prepared.upload)
        Indexer(here.storage, racing, here.keys).catchUp()
        assertEquals(FileStatus.READY, here.index.file(fileId)!!.status)
    }

    @Test
    fun uploadingFileWithoutAnUploadIsRepaired() = runBlocking {
        // A row left at UPLOADING by an earlier bug or a crash, with its upload already gone.
        val here = device()
        val fileId = here.upload("stuck.bin", BytesSource(ByteArray(10)))
        here.index.setStatus(fileId, FileStatus.UPLOADING)
        here.indexer.catchUp()
        assertEquals(FileStatus.READY, here.index.file(fileId)!!.status)
    }

    @Test
    fun catchUpReadsEveryPageAfterTheLastApplied() = runBlocking {
        val here = device()
        val there = device()
        here.upload("first.bin", BytesSource(ByteArray(3)))
        there.indexer.catchUp()
        // Pages hold 5 messages here; this is several pages after the last applied one.
        repeat(12) { here.storage.sendText("note $it") }
        val later = List(3) { here.upload("later-$it.bin", BytesSource(ByteArray(3))) }
        there.indexer.catchUp()
        assertTrue(later.all { there.index.file(it)?.status == FileStatus.READY })
    }

    @Test
    fun downloadWithTheWrongHashIsRejected() = runBlocking {
        val here = device()
        val fileId = here.upload("x.bin", BytesSource(ByteArray(10) { 7 }))
        val file = here.index.file(fileId)!!
        val keys = here.keys.transferKeys(fileId, file.sealedMeta)!!
        val wrong = TransferKeys(keys.dek, me.esdex.coffer.core.crypto.Bytes.of(ByteArray(32)))
        try {
            here.downloader.run(fileId, wrong, ByteArrayOutputStream())
            fail("expected ContentMismatchException")
        } catch (_: ContentMismatchException) {
        }
    }

    @Test
    fun lastAppliedNeverMovesBack() = runBlocking {
        val here = device()
        here.upload("a.bin", BytesSource(ByteArray(10)))
        here.upload("b.bin", BytesSource(ByteArray(10)))
        here.indexer.catchUp()
        val newest = here.index.lastApplied()!!
        // An old message delivered late, as an event buffered during catch-up can be.
        here.indexer.apply(cloud.allMessages().first())
        assertEquals(newest, here.index.lastApplied())
    }

    @Test
    fun partArrivingAfterFileDeleteDoesNotReviveIt() = runBlocking {
        val here = device()
        val prepared = here.prepare("gone.bin", BytesSource(ByteArray(10)))
        here.runner.run(prepared.upload, prepared.keys, BytesSource(ByteArray(10)))
        val fileId = prepared.upload.fileId!!
        here.deleter.delete(here.keys, fileId, now = 5)
        // The same part 0 sent again later: a straggler, or someone replaying an old message.
        here.storage.sendBlob(temp.newFile("cfr_aaaaaaaaaaaa.bin").apply { writeBytes(ByteArray(56)) }, prepared.upload.captions[0])
        val there = device()
        there.indexer.catchUp()
        assertNull(there.index.file(fileId))
        here.indexer.catchUp()
        assertNull(here.index.file(fileId))
    }

    @Test
    fun missingPartFailsTheDownload() = runBlocking {
        val here = device()
        val fileId = here.upload("x.bin", BytesSource(ByteArray(10)))
        val there = device()
        there.indexer.catchUp()
        val file = there.index.file(fileId)!!
        there.index.removePart(file.parts.getValue(0))
        try {
            there.download(fileId, ByteArrayOutputStream())
            fail("expected MissingPartException")
        } catch (e: MissingPartException) {
            assertEquals(0, e.partIndex)
        }
    }

    @Test
    fun longNamesAreFittedAndOddMimeReplaced() = runBlocking {
        val here = device()
        val long = "Отчёт ".repeat(60) + ".pdf"
        val fileId = here.upload(long, BytesSource(ByteArray(3)), mime = "text/пример")
        val meta = here.meta(here.index.file(fileId)!!)
        assertTrue(meta.name.endsWith(".pdf"))
        assertTrue(meta.name.toByteArray().size <= 255)
        assertEquals("application/octet-stream", meta.mime)
    }
}
