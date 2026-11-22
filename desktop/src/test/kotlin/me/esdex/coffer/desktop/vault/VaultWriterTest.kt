package me.esdex.coffer.desktop.vault

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.KeyRing
import me.esdex.coffer.core.crypto.LocalKeys
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.InMemoryVaultIndex
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.sync.Indexer
import me.esdex.coffer.core.sync.OpQueue
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.VaultOps
import me.esdex.coffer.desktop.drive.DriveTree
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** The drive's changes against a made-up Telegram (FakeCloud), with the indexer reading back what was sent. */
class VaultWriterTest {
    private val sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))
    private val master = SecretKey.adopt(ByteArray(32).also(SecureRandom()::nextBytes))
    private val keys = KeyRing.derive(sodium, master).let { VaultKeys(sodium, it.metaKey, it.indexKey, it.wrapKey, LocalKeys.derive(sodium, master)) }
    private val cloud = FakeCloud()
    private val storage = cloud.device()
    private val index = InMemoryVaultIndex()
    private val indexer = Indexer(storage, index, keys)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drafts = java.nio.file.Files.createTempDirectory("drafts").toFile()
    private val writer = VaultWriter(scope, index, keys, storage = { storage }, draftsDir = drafts, clock = { 1_762_531_200_000 })

    @After
    fun stop() {
        scope.cancel()
        drafts.deleteRecursively()
    }

    private fun texts() = cloud.allMessages().count { it.content is MessageContent.Text }

    private suspend fun until(what: String, check: suspend () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(20)
    }.also { assertTrue(what, check()) }

    private suspend fun file(name: String, folderId: Id? = null): Id {
        val fileId = Id.random(sodium)
        val meta = FileMeta(
            name, folderId, "text/plain", 1, 2,
            Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES)), Bytes.of(ByteArray(CofferFormat.WRAPPED_KEY_BYTES)),
        )
        index.putFile(fileId, 10, 1, keys.local.sealFileMeta(fileId, meta), FileStatus.READY)
        return fileId
    }

    private fun DriveTree.names(path: String = "/") = (this[path] as DriveTree.Folder).children

    @Test
    fun aFolderMadeShowsAtOnceAndOnlyOnceWhenItComesBack() = runBlocking {
        writer.start()
        writer.createFolder(null, "Photos")
        assertTrue("shown before the call returns", writer.tree.value["/Photos"] is DriveTree.Folder)
        until("sent") { index.ops().isEmpty() && texts() == 1 }
        indexer.catchUp()
        until("in the index") { index.folders().size == 1 }
        until("shown once") { writer.tree.value.names() == listOf("Photos") }
    }

    @Test
    fun renamesAndMovesShowAtOnceAndStayWhenTheyComeBack() = runBlocking {
        val fileId = file("a.txt")
        writer.start()
        until("listed") { writer.tree.value["/a.txt"] != null }
        writer.createFolder(null, "Docs")
        val tree = writer.tree.value
        writer.move(tree["/a.txt"]!!, tree["/"] as DriveTree.Folder, "a.txt", tree["/Docs"] as DriveTree.Folder, "b.txt", replacing = null)
        assertEquals(fileId, (writer.tree.value["/Docs/b.txt"] as DriveTree.File).fileId)
        assertNull(writer.tree.value["/a.txt"])
        until("sent") { index.ops().isEmpty() }
        indexer.catchUp()
        until("applied") { keys.local.openFileMeta(fileId, index.file(fileId)!!.sealedMeta)!!.name == "b.txt" }
        // The tree follows the index on its own coroutine.
        until("still there") { (writer.tree.value["/Docs/b.txt"] as? DriveTree.File)?.fileId == fileId }
        assertNull(writer.tree.value["/a.txt"])
    }

    @Test
    fun deletesInABurstGoAsOneMessage() = runBlocking {
        val ids = (1..12).map { file("f$it.txt") }
        writer.start()
        until("listed") { writer.tree.value.names().size == 12 }
        val tree = writer.tree.value
        for (n in 1..12) writer.delete(tree["/f$n.txt"]!!)
        assertEquals("gone from the drive at once", emptyList<String>(), writer.tree.value.names())
        until("sent") { index.ops().isEmpty() && texts() == 1 }
        indexer.catchUp()
        until("deleted in the index") { ids.all { index.file(it) == null } }
        assertEquals(emptyList<String>(), writer.tree.value.names())
    }

    @Test
    fun aFlushQueuesTheGatheredDeletesAtOnce() = runBlocking {
        file("a.txt")
        val tree = run {
            writer.start()
            until("listed") { writer.tree.value["/a.txt"] != null }
            writer.tree.value
        }
        writer.delete(tree["/a.txt"]!!)
        writer.flush()
        val queued = index.ops().flatMap { PendingOps.of(listOf(it), keys) } + cloudOps()
        assertTrue(queued.any { it is Op.FileDelete })
    }

    @Test
    fun whatAnEarlierSessionQueuedIsSent() = runBlocking {
        VaultOps(storage, keys, OpQueue(index)) { 1 }.createFolder("Left over", null)
        assertEquals(0, texts())
        writer.start()
        until("sent") { index.ops().isEmpty() && texts() == 1 }
    }

    /** Operations already in the made-up Telegram, read back with the vault's key. */
    private fun cloudOps(): List<Op> = cloud.allMessages().mapNotNull { (it.content as? MessageContent.Text)?.text }
        .flatMap { me.esdex.coffer.core.crypto.format.Captions.parse(it)?.let(keys.meta::decodeOps).orEmpty() }
}
