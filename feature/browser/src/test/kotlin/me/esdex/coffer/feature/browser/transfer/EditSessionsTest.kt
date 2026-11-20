package me.esdex.coffer.feature.browser.transfer

import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.sync.NameConflicts
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

    private companion object {
        const val HERE = 4242
        const val HOUR = EditSessions.IDLE_MILLIS
    }

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
        EditSessions.prepared(root, "upload-1", made, forward = true)
        val vault = tree(mapOf(report to meta("report.docx", docs)))
        assertEquals(EditSave("report.docx", docs, report, cancel = "upload-1", original = report), EditSessions.plan(EditSessions.get(root, session.id)!!, vault, setOf("upload-1")) { true })
    }

    @Test
    fun aSaveAfterTheLastOneWentThroughReplacesTheFileItMade() {
        val session = session()
        val made = id()
        EditSessions.queued(session, "upload-1", hash(2), original = null)
        EditSessions.prepared(root, "upload-1", made, forward = true)
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
    fun aFileMadeInThePickerComesInUnderItsOwnNameThenReplacesWhatItMade() {
        val made = EditSessions.begin(root, "notes.kdbx", "application/octet-stream", null, docs, Bytes.of(ByteArray(0)), now = 1_000)
        assertNull(made.original)
        // Taken meanwhile: numbered, never "(edited)".
        val taken = tree(mapOf(report to meta("Notes.kdbx", docs)))
        assertEquals(EditSave("notes (1).kdbx", docs, null, cancel = null, original = null), EditSessions.plan(made, taken, emptySet()) { false })
        assertEquals(EditSave("notes.kdbx", docs, null, cancel = null, original = null), EditSessions.plan(made, tree(emptyMap()), emptySet()) { false })

        EditSessions.queued(made, "upload-1", hash(2), original = null)
        val file = id()
        EditSessions.prepared(root, "upload-1", file, forward = true)
        val vault = tree(mapOf(file to meta("notes.kdbx", docs)))
        assertEquals(EditSave("notes.kdbx", docs, file, cancel = null, original = file), EditSessions.plan(EditSessions.get(root, made.id)!!, vault, emptySet()) { it == file })
    }

    @Test
    fun aPreparedSaveForwardsTheFileItReplacesAndItsSession() {
        val session = session()
        EditSessions.queued(session, "upload-1", hash(2), original = report)
        val made = id()
        EditSessions.prepared(root, "upload-1", made, forward = true)
        val forwards = DocumentForwards.read(EditSessions.forwardsFile(root))
        assertEquals(listOf(report, made), forwards.chain(EditSession.hex(report.toByteArray())))
        assertEquals(listOf(made), forwards.chain(session.id))
        // Another upload's preparing forwards nothing of this one.
        EditSessions.prepared(root, "upload-2", id(), forward = true)
        assertEquals(listOf(report, made), DocumentForwards.read(EditSessions.forwardsFile(root)).chain(EditSession.hex(report.toByteArray())))
        // The forwards file is no session.
        assertEquals(listOf(session.id), EditSessions.all(root).map { it.id })
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
        EditSessions.sweep(root, pending = setOf("upload-9"), now = 2_000 + EditSessions.IDLE_MILLIS, pid = HERE)
        val left = EditSessions.all(root).map { it.id }.toSet()
        assertEquals(setOf(dirty.id, sending.id, recent.id), left)
        assertFalse(idle.id in left)
        // Its upload done, the sending one goes too.
        EditSessions.sweep(root, pending = emptySet(), now = 2_000 + EditSessions.IDLE_MILLIS, pid = HERE)
        assertEquals(setOf(dirty.id, recent.id), EditSessions.all(root).map { it.id }.toSet())
    }

    @Test
    fun aCopyHandedOutForWritingStaysWhileOpenUpToADay() {
        // Audit 3, DATA3-2: an editor may keep the copy open an hour and more before it saves.
        val open = session().also { EditSessions.opened(root, it.id, HERE, now = 1_000) }
        EditSessions.sweep(root, emptySet(), now = 1_000 + 2 * HOUR, pid = HERE)
        assertEquals(listOf(open.id), EditSessions.all(root).map { it.id })
        // Past a day without a word, or held by a process that is gone, it is idle like any other.
        EditSessions.sweep(root, emptySet(), now = 2_000 + EditSessions.WRITING_MILLIS, pid = HERE)
        assertEquals(emptyList<String>(), EditSessions.all(root).map { it.id })
        val other = session().also { EditSessions.opened(root, it.id, HERE + 1, now = 1_000) }
        EditSessions.sweep(root, emptySet(), now = 1_000 + 2 * HOUR, pid = HERE)
        assertFalse(other.id in EditSessions.all(root).map { it.id })
    }

    @Test
    fun aSaveNobodyHeardIsSentNotSwept() {
        // Coffer killed while the editor held the copy: the close never came, the change is on disk.
        val session = session().also { EditSessions.opened(root, it.id, HERE + 1, now = 1_000) }
        session.copy.writeText("edited")
        session.copy.setLastModified(session.modified + 5_000)
        EditSessions.sweep(root, emptySet(), now = 1_000 + 2 * HOUR, pid = HERE)
        val after = EditSessions.get(root, session.id)!!
        assertTrue(after.dirty)
        assertNull(after.writer)
        // Saved, and nothing changed since: it goes in its time.
        EditSessions.queued(after, "upload-1", hash(2), original = null)
        EditSessions.sweep(root, emptySet(), now = 2_000 + 4 * HOUR, pid = HERE)
        assertNull(EditSessions.get(root, session.id))
    }

    @Test
    fun aCopyBeingFilledIsNoSaveAndIsLeftToItsDownload() {
        val filling = EditSessions.begin(root, "big.mkv", "video/x-matroska", report, docs, hash(1), now = 1_000, ready = false)
        filling.copy.writeText("half of it")
        filling.copy.setLastModified(filling.modified + 5_000)
        EditSessions.sweep(root, emptySet(), now = 1_000 + 2 * HOUR, pid = HERE)
        val still = EditSessions.get(root, filling.id)!!
        assertFalse(still.dirty)
        // Filled: what it holds now is the vault's, no save either.
        EditSessions.filled(root, filling.id, now = 1_000 + 3 * HOUR)
        EditSessions.sweep(root, emptySet(), now = 1_000 + 3 * HOUR + 1, pid = HERE)
        assertFalse(EditSessions.get(root, filling.id)!!.dirty)
        // A download that never ends gives it up after a day.
        val stuck = EditSessions.begin(root, "stuck.bin", "application/octet-stream", report, docs, hash(1), now = 1_000, ready = false)
        EditSessions.sweep(root, emptySet(), now = 2_000 + EditSessions.FILLING_MILLIS, pid = HERE)
        assertNull(EditSessions.get(root, stuck.id))
    }

    @Test
    fun aWriteThatFailedIsNoSaveAndItsCopyGoes() {
        // Audit 3, Q3-2: an editor that crashed mid-write left the copy cut short.
        val session = session().also { EditSessions.opened(root, it.id, HERE, now = 1_000) }
        // Written and closed once, not sent yet (locked), then opened again and cut short.
        EditSessions.written(root, session.id, now = 1_500)
        session.copy.writeText("half")
        EditSessions.failed(root, session.id, now = 2_000)
        val after = EditSessions.get(root, session.id)!!
        assertTrue(after.broken)
        assertFalse(after.dirty)
        assertNull(after.writer)
        // Not while an earlier save still reads the copy; then at once, not after the hour.
        EditSessions.queued(after, "upload-1", hash(2), original = null)
        EditSessions.sweep(root, setOf("upload-1"), now = 2_001, pid = HERE)
        assertTrue(EditSessions.get(root, session.id)!!.broken)
        EditSessions.sweep(root, emptySet(), now = 2_001, pid = HERE)
        assertNull(EditSessions.get(root, session.id))
    }

    @Test
    fun aCopyNamedLikeTheSessionsOwnFileKeepsItsName() {
        // Audit 3, AND3-11: Save as "session.properties" lost the copy among the session's own files.
        for (name in listOf("session.properties", "session.properties.tmp")) {
            val session = EditSessions.begin(root, name, "text/plain", null, null, Bytes.of(ByteArray(0)), now = 1)
            assertEquals(NameConflicts.numbered(name, isFile = true, setOf(name)), session.copy.name)
            assertTrue(session.copy.exists())
        }
    }

    @Test
    fun noNameShowsInASavesText() {
        assertFalse("Secret" in EditSave("Secret plan.docx", null, null, null, null).toString())
    }
}
