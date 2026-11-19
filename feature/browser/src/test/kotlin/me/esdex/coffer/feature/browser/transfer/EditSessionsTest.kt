package me.esdex.coffer.feature.browser.transfer

import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.sync.VaultTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/** Files edited in another app (decision 2026-11-19): where a save goes, written out by hand. */
class EditSessionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun id() = Id.of(Random.nextBytes(16))
    private fun hash(seed: Int) = Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES) { seed.toByte() })
    private fun meta(name: String, folder: Id?) =
        FileMeta(name, folder, "application/msword", 1, 1, hash(0), Bytes.of(ByteArray(CofferFormat.WRAPPED_KEY_BYTES)))

    private val docs = id()
    private val work = id()
    private val report = id()
    private val root by lazy { temp.newFolder("edit") }

    private fun session(original: Id = report, folder: Id? = docs) =
        EditSessions.begin(root, "report.docx", "application/msword", original, folder, hash(1), now = 1_000)

    private fun tree(files: Map<Id, FileMeta>, folders: Map<Id, LocalFolder> = mapOf(docs to LocalFolder("Docs", null), work to LocalFolder("Work", null))) =
        VaultTree(folders, files)

    @Test
    fun aSessionKeepsWhatASaveNeedsAndNothingElse() {
        val session = session()
        assertEquals("report.docx", session.copy.name)
        assertEquals(report, session.original)
        assertEquals(docs, session.folder)
        assertEquals(hash(1), session.savedHash)
        assertFalse(session.dirty)
        // What another app holds in its URI is checked before it names a folder on disk.
        assertNull(EditSessions.get(root, "../" + session.id))
        assertNull(EditSessions.get(root, "./" + session.id))
        assertNull(EditSessions.get(root, "nope"))
        assertEquals(session.id, EditSessions.get(root, session.id)?.id)

        EditSessions.written(root, session.id, now = 5_000)
        val written = EditSessions.get(root, session.id)!!
        assertTrue(written.dirty)
        assertEquals(5_000, written.touched)
    }

    @Test
    fun aSaveReplacesTheFileWhereverItIsNow() {
        // Renamed and moved since the edit began: the same file, followed.
        val vault = tree(mapOf(report to meta("Q3 report.docx", work)))
        assertEquals(EditSave("Q3 report.docx", work, report, cancel = null, original = report), EditSessions.plan(session(), vault, emptySet()) { false })
    }

    @Test
    fun aSaveWhileTheLastOneIsQueuedCancelsItAndReplacesTheSameFile() {
        val session = session()
        val made = id()
        EditSessions.queued(session, "upload-1", hash(2), original = null)
        EditSessions.prepared(root, "upload-1", made)
        val vault = tree(mapOf(report to meta("report.docx", docs)))
        assertEquals(EditSave("report.docx", docs, report, cancel = "upload-1", original = report), EditSessions.plan(EditSessions.get(root, session.id)!!, vault, setOf("upload-1")) { true })
    }

    @Test
    fun aSaveAfterTheLastOneWentThroughReplacesTheFileItMade() {
        val session = session()
        val made = id()
        EditSessions.queued(session, "upload-1", hash(2), original = null)
        EditSessions.prepared(root, "upload-1", made)
        val vault = tree(mapOf(made to meta("report.docx", docs)))
        val reloaded = EditSessions.get(root, session.id)!!
        assertEquals(EditSave("report.docx", docs, made, cancel = null, original = made), EditSessions.plan(reloaded, vault, emptySet()) { it == made })
        // One that was given up on, its source changed under it, made nothing: the original stays the one.
        val withOriginal = tree(mapOf(report to meta("report.docx", docs)))
        assertEquals(report, EditSessions.plan(reloaded, withOriginal, emptySet()) { false }.replaces)
    }

    @Test
    fun aSaveFindingTheFileGoneComesInBesideItAsEdited() {
        val other = id()
        val vault = tree(mapOf(other to meta("report (edited).docx", docs)))
        assertEquals(EditSave("report (edited) (1).docx", docs, null, cancel = null, original = null), EditSessions.plan(session(), vault, emptySet()) { false })
        // Its folder gone too: at the top level.
        assertEquals(EditSave("report (edited).docx", null, null, cancel = null, original = null), EditSessions.plan(session(), tree(emptyMap(), emptyMap()), emptySet()) { false })
    }

    @Test
    fun editedNamesKeepTheExtension() {
        assertEquals("report (edited).docx", EditSessions.editedName("report.docx", emptySet()))
        assertEquals("Makefile (edited)", EditSessions.editedName("Makefile", emptySet()))
        assertEquals("report (edited) (2).docx", EditSessions.editedName("report.docx", setOf("report (edited).docx", "report (edited) (1).docx")))
    }

    @Test
    fun onlySessionsWithNothingToSendAndIdleForAnHourGo() {
        val idle = session()
        val dirty = session().also { EditSessions.written(root, it.id, now = 1_000) }
        val sending = session().also { EditSessions.queued(it, "upload-9", hash(3), original = null) }
        val recent = EditSessions.begin(root, "a.txt", "text/plain", id(), null, hash(1), now = 1_000 + EditSessions.IDLE_MILLIS)
        EditSessions.sweep(root, pending = setOf("upload-9"), now = 2_000 + EditSessions.IDLE_MILLIS)
        val left = EditSessions.all(root).map { it.id }.toSet()
        assertEquals(setOf(dirty.id, sending.id, recent.id), left)
        assertFalse(idle.id in left)
        // Its upload done, the sending one goes too.
        EditSessions.sweep(root, pending = emptySet(), now = 2_000 + EditSessions.IDLE_MILLIS)
        assertEquals(setOf(dirty.id, recent.id), EditSessions.all(root).map { it.id }.toSet())
    }
}
