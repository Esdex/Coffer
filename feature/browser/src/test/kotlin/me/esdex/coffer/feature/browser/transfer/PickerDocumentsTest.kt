package me.esdex.coffer.feature.browser.transfer

import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.feature.browser.transfer.PickerDocuments.Content
import me.esdex.coffer.feature.browser.transfer.PickerDocuments.Doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/** Coffer in the system file picker (decision 2026-11-20): what a document id stands for, written out by hand. */
class PickerDocumentsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun id() = Id.of(Random.nextBytes(16))
    private fun hash(seed: Int) = Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES) { seed.toByte() })
    private fun hex(id: Id) = EditSession.hex(id.toByteArray())
    private val root by lazy { temp.newFolder("edit") }
    private val forwards by lazy { EditSessions.forwardsFile(root) }

    @Test
    fun idsGoBothWaysAndAnythingElseIsNoDocument() {
        val file = id()
        val session = EditSessions.begin(root, "a.txt", "text/plain", null, null, Bytes.of(ByteArray(0)), now = 1)
        for (doc in listOf(Doc.Root, Doc.Folder(file), Doc.File(file), Doc.Made(session.id))) {
            assertEquals(doc, PickerDocuments.parse(PickerDocuments.id(doc)))
        }
        assertEquals("f:" + hex(file), PickerDocuments.id(Doc.File(file)))
        // What another app hands back is checked before it names anything.
        for (bad in listOf("", "f:", "f:" + hex(file).uppercase(), "f:" + hex(file) + "0", "f:" + hex(file).drop(2), "d:../x", "n:../" + session.id.drop(3), "x:" + hex(file), "Root", "root/")) {
            assertNull(bad, PickerDocuments.parse(bad))
        }
    }

    @Test
    fun aFileReadsFromTheVaultUnlessACopyIsNewer() {
        val file = id()
        val doc = Doc.File(file)
        val ready = { it: Id -> it == file }
        assertEquals(Content.Stored(file), PickerDocuments.content(doc, listOf(file), emptyList(), ready, emptySet()))
        // Begun from the file and not written: the vault's is as new.
        val session = EditSessions.begin(root, "a.txt", "text/plain", file, null, hash(1), now = 1)
        assertEquals(Content.Stored(file), PickerDocuments.content(doc, listOf(file), listOf(session), ready, emptySet()))
        assertNull(PickerDocuments.writable(doc, listOf(file), listOf(session), current = null))
        // Written: the copy, until its upload is through.
        EditSessions.written(root, session.id, now = 2)
        val written = EditSessions.get(root, session.id)!!
        assertEquals(Content.Copy(written), PickerDocuments.content(doc, listOf(file), listOf(written), ready, emptySet()))
        EditSessions.queued(written, "upload-1", hash(2), original = file)
        val queued = EditSessions.get(root, session.id)!!
        assertEquals(Content.Copy(queued), PickerDocuments.content(doc, listOf(file), listOf(queued), ready, setOf("upload-1")))
        assertEquals(queued.id, PickerDocuments.writable(doc, listOf(file), listOf(queued), current = file)?.id)
        // Through, and the new file whole: the vault again, where the document went.
        val made = id()
        EditSessions.prepared(root, "upload-1", made, now = 3)
        val chain = DocumentForwards.read(forwards).chain(hex(file))
        assertEquals(listOf(file, made), chain)
        val done = EditSessions.get(root, session.id)!!
        assertEquals(Content.Stored(made), PickerDocuments.content(doc, chain, listOf(done), { it == made }, emptySet()))
        // Still on its way: the copy; given up on: the file it was to replace, still there.
        assertEquals(Content.Copy(done), PickerDocuments.content(doc, chain, listOf(done), ready, setOf("upload-1")))
        assertEquals(Content.Stored(file), PickerDocuments.content(doc, chain, listOf(done), ready, emptySet()))
        // Gone altogether: nothing.
        assertNull(PickerDocuments.content(doc, chain, emptyList(), { false }, emptySet()))
    }

    @Test
    fun aFileMadeInThePickerIsItsCopyUntilItIsInTheVault() {
        val session = EditSessions.begin(root, "new.kdbx", "application/octet-stream", null, null, Bytes.of(ByteArray(0)), now = 1)
        val doc = Doc.Made(session.id)
        assertEquals(Content.Copy(session), PickerDocuments.content(doc, emptyList(), listOf(session), { false }, emptySet()))
        assertEquals(session.id, PickerDocuments.writable(doc, emptyList(), listOf(session), current = null)?.id)
        EditSessions.queued(session, "upload-1", hash(2), original = null)
        val file = id()
        EditSessions.prepared(root, "upload-1", file, now = 2)
        val chain = DocumentForwards.read(forwards).chain(session.id)
        assertEquals(listOf(file), chain)
        val sent = EditSessions.get(root, session.id)!!
        assertEquals(Content.Stored(file), PickerDocuments.content(doc, chain, listOf(sent), { it == file }, emptySet()))
        // Its session gone after the hour: the file still.
        assertEquals(Content.Stored(file), PickerDocuments.content(doc, chain, emptyList(), { it == file }, emptySet()))
        assertNull(PickerDocuments.writable(doc, chain, emptyList(), current = file))
        // Another session's copy is not this one's.
        val other = EditSessions.begin(root, "other.txt", "text/plain", null, null, Bytes.of(ByteArray(0)), now = 1)
        assertNull(PickerDocuments.content(Doc.Made(other.id), emptyList(), listOf(session), { false }, emptySet()))
    }

    @Test
    fun forwardsFollowEverySaveAndSettleOnTheFileTheyEndAt() {
        val (a, b, c) = List(3) { id() }
        DocumentForwards.add(forwards, hex(a), b, now = 1)
        DocumentForwards.add(forwards, hex(b), c, now = 2)
        assertEquals(listOf(a, b, c), DocumentForwards.read(forwards).chain(hex(a)))
        assertEquals(listOf(c), DocumentForwards.read(forwards).chain(hex(c)))
        DocumentForwards.settle(forwards, listOf(hex(a), hex(b)), c, now = 3)
        assertEquals(listOf(a, c), DocumentForwards.read(forwards).chain(hex(a)))
        // A loop, which nothing makes, ends the chain rather than the provider.
        DocumentForwards.add(forwards, hex(c), a, now = 4)
        assertEquals(listOf(a, c), DocumentForwards.read(forwards).chain(hex(a)))
    }

    @Test
    fun forwardsAreKeptToTheLimitTheLeastUsedGoingFirst() {
        val kept = id()
        val first = id()
        DocumentForwards.add(forwards, hex(first), kept, now = 0)
        val start = id()
        DocumentForwards.add(forwards, hex(start), kept, now = 1)
        repeat(DocumentForwards.LIMIT - 1) { DocumentForwards.add(forwards, hex(id()), id(), now = 10L + it) }
        // Used again: settled, it counts as new.
        DocumentForwards.settle(forwards, listOf(hex(start)), kept, now = 5_000)
        DocumentForwards.add(forwards, hex(id()), id(), now = 6_000)
        val read = DocumentForwards.read(forwards)
        assertEquals(listOf(start, kept), read.chain(hex(start)))
        assertEquals(listOf(first), read.chain(hex(first)))
    }

    @Test
    fun aBrokenCopyIsNeitherReadNorWrittenTo() {
        // Audit 3, Q3-2: a write that failed left no save; the document is the vault's file again.
        val file = id()
        val session = EditSessions.begin(root, "a.txt", "text/plain", file, null, hash(1), now = 1)
        // A save still on its way, then a write that failed over it.
        EditSessions.written(root, session.id, now = 2)
        EditSessions.queued(EditSessions.get(root, session.id)!!, "upload-1", hash(2), original = file)
        EditSessions.failed(root, session.id, now = 3)
        val broken = listOf(EditSessions.get(root, session.id)!!)
        assertEquals(Content.Stored(file), PickerDocuments.content(Doc.File(file), listOf(file), broken, { it == file }, setOf("upload-1")))
        assertNull(PickerDocuments.writable(Doc.File(file), listOf(file), broken, current = file))
        val made = EditSessions.begin(root, "new.txt", "text/plain", null, null, Bytes.of(ByteArray(0)), now = 1)
        EditSessions.failed(root, made.id, now = 2)
        val madeBroken = listOf(EditSessions.get(root, made.id)!!)
        assertNull(PickerDocuments.content(Doc.Made(made.id), emptyList(), madeBroken, { false }, emptySet()))
        assertNull(PickerDocuments.writable(Doc.Made(made.id), emptyList(), madeBroken, current = null))
    }

    @Test
    fun opensToWriteTheSameFileShareOneCopy() {
        // Audit 3, AND3-4: each one decrypted the whole file again.
        val file = id()
        val filling = EditSessions.begin(root, "a.kdbx", "application/octet-stream", file, null, hash(1), now = 1, ready = false)
        assertNull(PickerDocuments.writable(Doc.File(file), listOf(file), listOf(filling), current = file))
        EditSessions.filled(root, filling.id, now = 2)
        val filled = EditSessions.get(root, filling.id)!!
        assertEquals(filled.id, PickerDocuments.writable(Doc.File(file), listOf(file), listOf(filled), current = file)?.id)
        // Filled from a version that has been replaced since: not that one.
        val newer = id()
        assertNull(PickerDocuments.writable(Doc.File(file), listOf(file, newer), listOf(filled), current = newer))
    }
}
