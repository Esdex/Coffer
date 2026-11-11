package me.esdex.coffer.core.sync

import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Format v2 in a whole vault (SPEC §4.5): padded files, files not padded, and files from a client that
 * still writes version 1, side by side, read on another device from the history and from a snapshot.
 * What Telegram sees is taken from the fake cloud's documents and captions; the expected sizes are
 * worked out by hand.
 */
class FormatV2SyncTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud()

    private fun device(writeVersion: Int = Captions.V2) = Device(cloud, keysOf(master, writeVersion), temp.newFolder())

    // 1 000 003 bytes: below 1 MiB, one chunk. padme: 2^19 <= size < 2^20, step 2^(19 - 5), so 62 * 16 384.
    private val size = 1_000_003L
    private val paddedBlob = 1_015_808L + 40
    private val plainBlob = 1_000_003L + 40

    private fun captions() = cloud.allMessages().mapNotNull { m ->
        when (val c = m.content) {
            is MessageContent.Blob -> c.caption
            is MessageContent.Text -> c.text
            MessageContent.Other -> null
        }
    }

    private fun blobSizes() = cloud.allMessages().mapNotNull { (it.content as? MessageContent.Blob)?.size }

    private suspend fun Device.sha(fileId: Id) = HashingSink().also { download(fileId, it) }.hex()

    @Test
    fun paddedPlainAndOlderFilesAllReadBackWhole() = runBlocking {
        val here = device()
        val older = device(Captions.V1)
        val source = PatternSource(size)
        val expected = sha256(source.open())
        val padded = here.upload("padded.bin", source)
        val plain = here.upload("plain.bin", source, padSizes = false)
        val old = older.upload("old.bin", source, padSizes = false)

        // What Telegram holds: one document padded, two of the file's exact size plus the chunk's 40 bytes.
        assertEquals(listOf(paddedBlob, plainBlob, plainBlob), blobSizes())
        // Every version 2 blob caption is one length, the older client's is its own.
        val blobCaptions = captions().filter { Captions.parse(it)?.kind == RecordKind.BLOB }
        assertEquals(listOf(2, 2, 1), blobCaptions.map { Captions.parse(it)!!.version })
        assertEquals(setOf(871), blobCaptions.take(2).map { it.length }.toSet())

        val there = device()
        there.indexer.catchUp()
        val files = there.index.files().associateBy { it.fileId }
        assertEquals(listOf(true, false, false), listOf(padded, plain, old).map { files.getValue(it).padded })
        assertTrue(files.values.all { it.status == FileStatus.READY })
        for (id in listOf(padded, plain, old)) assertEquals(expected, there.sha(id))
    }

    @Test
    fun aSnapshotKeepsWhichFilesArePadded() = runBlocking {
        val here = device()
        val source = PatternSource(size)
        val expected = sha256(source.open())
        val padded = here.upload("padded.bin", source)
        val plain = here.upload("plain.bin", source, padSizes = false)
        here.indexer.catchUp()
        assertTrue(here.snapshotter.snapshotIfNeeded())
        val snapshot = captions().map { Captions.parse(it) }.single { it?.kind == RecordKind.INDEX }!!
        assertEquals(Captions.V2, snapshot.version)

        val there = device()
        assertTrue(there.indexer.catchUp().snapshotUsed)
        val files = there.index.files().associateBy { it.fileId }
        assertEquals(true, files.getValue(padded).padded)
        assertEquals(false, files.getValue(plain).padded)
        assertEquals(expected, there.sha(padded))
        assertEquals(expected, there.sha(plain))
    }

    @Test
    fun aRenamedPaddedFileStaysPadded() = runBlocking {
        val here = device()
        val source = PatternSource(size)
        val expected = sha256(source.open())
        val padded = here.upload("padded.bin", source)
        here.ops.renameFile(padded, "renamed.bin")
        val there = device()
        there.indexer.catchUp()
        val file = there.index.file(padded)!!
        assertEquals("renamed.bin", there.meta(file).name)
        assertEquals(true, file.padded)
        assertEquals(expected, there.sha(padded))
        // Operations are one length too, whatever the name.
        val ops = captions().filter { Captions.parse(it)?.kind == RecordKind.OP }
        assertTrue(ops.isNotEmpty() && ops.all { it.length == 539 })
    }

    @Test
    fun anUploadInProgressStaysPaddedWhenASnapshotIsLoaded() = runBlocking {
        val other = device()
        other.upload("first.bin", PatternSource(1000))
        other.indexer.catchUp()
        val source = PatternSource(size)
        val expected = sha256(source.open())
        // This device starts an upload before its first catch-up; the snapshot does not know the file yet.
        val here = device()
        val prepared = here.prepare("mine.bin", source)
        val mine = prepared.upload.fileId!!
        assertTrue(other.snapshotter.snapshotIfNeeded())
        assertTrue(here.indexer.catchUp().snapshotUsed)
        assertEquals(true, here.index.file(mine)!!.padded)
        here.runner.run(prepared.upload, prepared.keys, source)
        val there = device()
        there.indexer.catchUp()
        assertEquals(expected, there.sha(mine))
    }

    @Test
    fun anUploadAlreadyInASnapshotStaysPadded() = runBlocking {
        val source = PatternSource(size)
        val here = device()
        val prepared = here.prepare("mine.bin", source)
        val mine = prepared.upload.fileId!!
        // Its one part reaches Telegram, the upload not yet finished; another device snapshots the file.
        val part = temp.newFile()
        part.outputStream().use { out ->
            me.esdex.coffer.core.crypto.ContentEncryptor(sodium, prepared.keys.dek, mine, size, padded = true).encryptPart(0, source.open(), out)
        }
        here.storage.sendBlob(part, prepared.upload.captions[0])
        val other = device()
        other.indexer.catchUp()
        assertTrue(other.snapshotter.snapshotIfNeeded())
        assertTrue(here.indexer.catchUp().snapshotUsed)
        val row = here.index.file(mine)!!
        assertEquals(FileStatus.UPLOADING, row.status)
        assertEquals(true, row.padded)
    }

    @Test
    fun aPaddedFileReadsInPlaceToItsLastByteAndNoFurther() = runBlocking {
        val here = device()
        // Past one chunk, so the padding fills most of a second one: 1 MiB + 1 is padded to 1 081 344.
        val big = 1_048_577L
        val fileId = here.upload("edge.bin", PatternSource(big))
        val file = here.index.file(fileId)!!
        assertEquals(listOf(1_081_344L + 2 * 40), blobSizes())
        here.keys.reader(here.storage, file, here.keys.transferKeys(fileId, file.sealedMeta)!!).use { reader ->
            assertEquals(big, reader.size)
            val tail = ByteArray(8)
            assertEquals(3, reader.read(big - 3, tail, 0, 8))
            for (i in 0 until 3) assertEquals(((big - 3 + i) % 251).toByte(), tail[i])
            assertEquals(-1, reader.read(big, tail, 0, 1))
        }
    }
}
