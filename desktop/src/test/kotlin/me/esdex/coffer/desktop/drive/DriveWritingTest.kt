package me.esdex.coffer.desktop.drive

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.KeyRing
import me.esdex.coffer.core.crypto.LocalKeys
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.InMemoryVaultIndex
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.sync.DownloadRunner
import me.esdex.coffer.core.sync.Indexer
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.reader
import me.esdex.coffer.core.sync.transferKeys
import me.esdex.coffer.desktop.vault.VaultWriter
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Files written on the drive, end to end: through the kernel with java.nio, into drafts, up to a made-up
 * Telegram (FakeCloud) by the real writer. What arrived is checked from a second device, which catches up
 * from the cloud and downloads the file as the phone would, against the JDK's SHA-256 of what was written.
 */
class DriveWritingTest {
    private val sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))
    private val master = SecretKey.adopt(ByteArray(32).also(SecureRandom()::nextBytes))
    private val keys = KeyRing.derive(sodium, master).let { VaultKeys(sodium, it.metaKey, it.indexKey, it.wrapKey, LocalKeys.derive(sodium, master)) }
    private val cloud = FakeCloud()
    private val storage = cloud.device()
    private val index = InMemoryVaultIndex()
    private val indexer = Indexer(storage, index, keys)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var work: Path
    private lateinit var drafts: File
    private lateinit var writer: VaultWriter
    private lateinit var at: Path
    private lateinit var mounted: VaultDrive.Mounted

    @Before
    fun mount() = runBlocking {
        assumeTrue("no FUSE here", File("/dev/fuse").exists())
        work = Files.createTempDirectory(Path.of("build").toAbsolutePath().also(Files::createDirectories), "writing")
        drafts = work.resolve("staging").toFile()
        at = Files.createDirectories(work.resolve("drive"))
        writer = VaultWriter(scope, index, keys, storage = { storage }, draftsDir = drafts)
        writer.start()
        mounted = VaultDrive.mount(
            at,
            tree = { writer.tree.value },
            open = { file ->
                runBlocking {
                    val indexed = index.file(file.fileId)!!
                    val transfer = keys.transferKeys(file.fileId, indexed.sealedMeta)!!
                    val reader = keys.reader(storage, indexed, transfer)
                    OpenFile(read = reader::read, onClose = { reader.close(); transfer.close() })
                }
            },
            edits = { writer },
            space = work,
        )
    }

    @After
    fun unmount() {
        if (::mounted.isInitialized) runCatching { mounted.close() }
        scope.cancel()
        if (::work.isInitialized) work.toFile().deleteRecursively()
    }

    private suspend fun idle() = index.uploads().isEmpty() && index.ops().isEmpty() && drafts.list().orEmpty().none { it != "parts" }

    /**
     * Waits until nothing is left to upload or send, twice in a row: an upload ends a moment before what
     * changed while it went up is queued. Then the indexer reads back all that went.
     */
    private suspend fun settled() = withTimeout(30_000) {
        while (true) {
            indexer.catchUp()
            if (idle()) {
                delay(QUIET_CHECK_MS)
                if (idle()) {
                    indexer.catchUp()
                    return@withTimeout
                }
            }
            delay(100)
        }
    }

    /** The whole files the vault holds, by name, as this device's index has them; two under one name fail. */
    private suspend fun names(): Map<String, Id> {
        val named = index.files().filter { it.status == FileStatus.READY }.map { keys.local.openFileMeta(it.fileId, it.sealedMeta)!!.name to it.fileId }
        assertEquals("one file per name", named.size, named.map { it.first }.toSet().size)
        return named.toMap()
    }

    /** What a second device downloads under [name], after catching up from the cloud: the phone's view. */
    private suspend fun fromAnotherDevice(name: String): ByteArray {
        val there = cloud.device()
        val thereIndex = InMemoryVaultIndex()
        Indexer(there, thereIndex, keys).catchUp()
        val file = thereIndex.files().single { keys.local.openFileMeta(it.fileId, it.sealedMeta)?.name == name && it.status == FileStatus.READY }
        val out = ByteArrayOutputStream()
        DownloadRunner(sodium, there, thereIndex).run(file.fileId, keys.transferKeys(file.fileId, file.sealedMeta)!!, out)
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun blobs() = cloud.allMessages().count { it.content is MessageContent.Blob }

    @Test
    fun aNewFileGoesUpWholeAndReadsTheSameEverywhere() = runBlocking {
        val bytes = Random(1).nextBytes(200_000)
        Files.write(at.resolve("photo.bin"), bytes)
        // A draft at once: there, with its size, before anything went up.
        assertEquals(bytes.size.toLong(), Files.size(at.resolve("photo.bin")))
        settled()
        assertEquals(setOf("photo.bin"), names().keys)
        assertArrayEquals(bytes, Files.readAllBytes(at.resolve("photo.bin")))
        assertEquals(sha256(bytes), sha256(fromAnotherDevice("photo.bin")))
        assertTrue("the draft's copy is gone", drafts.list().orEmpty().none { it != "parts" })
    }

    @Test
    fun overwritingAFileReplacesIt() = runBlocking {
        Files.write(at.resolve("notes.txt"), "first".toByteArray())
        settled()
        val first = names().getValue("notes.txt")
        Files.write(at.resolve("notes.txt"), "second, longer".toByteArray())
        settled()
        val files = names()
        assertEquals(setOf("notes.txt"), files.keys)
        assertTrue("a new file took its place", files.getValue("notes.txt") != first)
        assertEquals("second, longer", String(Files.readAllBytes(at.resolve("notes.txt"))))
        assertEquals("second, longer", String(fromAnotherDevice("notes.txt")))
    }

    @Test
    fun anAppendKeepsWhatWasThere() = runBlocking {
        Files.write(at.resolve("log.txt"), "one\n".toByteArray())
        settled()
        Files.write(at.resolve("log.txt"), "two\n".toByteArray(), StandardOpenOption.APPEND)
        settled()
        assertEquals("one\ntwo\n", String(fromAnotherDevice("log.txt")))
    }

    @Test
    fun aSaveThatRenamesOverTheOldFileReplacesIt() = runBlocking {
        Files.write(at.resolve("doc.txt"), "v1".toByteArray())
        settled()
        // As editors save: a temporary file, then renamed over the real one.
        Files.write(at.resolve(".doc.txt.tmp"), "v2".toByteArray())
        Files.move(at.resolve(".doc.txt.tmp"), at.resolve("doc.txt"), StandardCopyOption.ATOMIC_MOVE)
        assertEquals("v2", String(Files.readAllBytes(at.resolve("doc.txt"))))
        settled()
        assertEquals(setOf("doc.txt"), names().keys)
        assertEquals("v2", String(fromAnotherDevice("doc.txt")))
    }

    @Test
    fun aFileDeletedBeforeItWentUpSendsNothing() = runBlocking {
        Files.write(at.resolve("scratch.txt"), "gone soon".toByteArray())
        Files.delete(at.resolve("scratch.txt"))
        delay(3_000)
        settled()
        assertEquals(0, blobs())
        assertEquals(emptyMap<String, Id>(), names())
    }

    @Test
    fun aFileCutShortOnTheDriveIsCutShortInTheVault() = runBlocking {
        Files.write(at.resolve("cut.txt"), "0123456789".toByteArray())
        settled()
        RandomAccessFile(at.resolve("cut.txt").toFile(), "rw").use { it.setLength(4) }
        settled()
        assertEquals("0123", String(fromAnotherDevice("cut.txt")))
    }

    /** Holds every upload at its start until [release] completes; [started] says one got there. */
    private fun holdUploads(): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        cloud.onTransfer = {
            started.complete(Unit)
            release.await()
        }
        return started to release
    }

    @Test
    fun aDraftRenamedWhileItGoesUpEndsUnderItsNewName() = runBlocking {
        val (started, release) = holdUploads()
        Files.write(at.resolve("draft.txt"), "content".toByteArray())
        withTimeout(10_000) { started.await() }
        Files.move(at.resolve("draft.txt"), at.resolve("final.txt"), StandardCopyOption.ATOMIC_MOVE)
        release.complete(Unit)
        settled()
        assertEquals(setOf("final.txt"), names().keys)
        assertEquals("content", String(fromAnotherDevice("final.txt")))
    }

    @Test
    fun aDraftWrittenAgainWhileItGoesUpIsTakenBackAndGoesUpWhole() = runBlocking {
        val (started, release) = holdUploads()
        Files.write(at.resolve("grow.txt"), "one ".toByteArray())
        withTimeout(10_000) { started.await() }
        Files.write(at.resolve("grow.txt"), "two".toByteArray(), StandardOpenOption.APPEND)
        release.complete(Unit)
        settled()
        assertEquals(setOf("grow.txt"), names().keys)
        assertEquals("one two", String(fromAnotherDevice("grow.txt")))
        // What went up of the first try was taken back: one file's parts are all that is left.
        assertEquals(1, blobs())
    }

    @Test
    fun anUploadCutShortByAQuitGoesUpAtTheNextStart() = runBlocking {
        val (started, _) = holdUploads()
        Files.write(at.resolve("later.txt"), "kept".toByteArray())
        withTimeout(10_000) { started.await() }
        // Quit: the drive and this session go, the draft and its queued upload stay.
        mounted.close()
        cloud.onTransfer = {}
        // A quit ends the process; here the session's last work, taking back what went up, is waited for.
        scope.coroutineContext.job.cancelAndJoin()
        val again = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            VaultWriter(again, index, keys, storage = { storage }, draftsDir = drafts).start()
            settled()
            assertEquals(setOf("later.txt"), names().keys)
            assertEquals("kept", String(fromAnotherDevice("later.txt")))
        } finally {
            again.cancel()
        }
    }

    @Test
    fun aFileMadeInAFolderGoesThere() = runBlocking {
        Files.createDirectory(at.resolve("Docs"))
        Files.write(at.resolve("Docs/inside.txt"), "in".toByteArray())
        settled()
        val fileId = names().getValue("inside.txt")
        val folderId = keys.local.openFileMeta(fileId, index.file(fileId)!!.sealedMeta)!!.folderId
        assertEquals("Docs", keys.local.openFolder(folderId!!, index.folder(folderId)!!.sealedMeta)!!.name)
        assertArrayEquals("in".toByteArray(), Files.readAllBytes(at.resolve("Docs/inside.txt")))
    }

    private companion object {
        const val QUIET_CHECK_MS = 500L
    }
}
