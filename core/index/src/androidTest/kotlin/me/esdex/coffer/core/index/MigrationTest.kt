package me.esdex.coffer.core.index

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.MessageId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The index is rebuildable, but the transfer queue in it is not: updates keep it (schema 1 to the current one). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), IndexDatabase::class.java)

    @Test
    fun version1KeepsItsRowsInTheCurrentVersion() {
        val fileId = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // App storage opens only after the first unlock since boot: unlock the emulator before running this.
        context.deleteDatabase(name)
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO files VALUES (?, 10, 1, ?, 'READY')", arrayOf(fileId, byteArrayOf(1, 2, 3)))
            db.execSQL("INSERT INTO parts VALUES (?, 0, 1048576)", arrayOf(fileId))
            db.execSQL("INSERT INTO uploads VALUES ('u1', ?, NULL, '', NULL)", arrayOf(byteArrayOf(4, 5)))
            db.execSQL("INSERT INTO sync_state VALUES ('lastAppliedMessageId', 1048576)")
        }
        // Validates the migrated schema against 11.json.
        helper.runMigrationsAndValidate(name, 11, true).close()

        // Audit 1, M9: the plain database is turned into an encrypted one, its rows kept.
        val file = context.getDatabasePath(name)
        assertTrue(RoomVaultIndex.isPlain(file))
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val index = RoomVaultIndex(context, key, name)
        runBlocking {
            val file = index.files().single()
            assertEquals(10L, file.plainSize)
            // Written before format v2: not padded.
            assertEquals(false, file.padded)
            assertEquals(FileStatus.READY, file.status)
            assertEquals(1048576L, file.parts.getValue(0).value)
            assertArrayEquals(byteArrayOf(4, 5), index.uploads().single().sealedRequest)
            assertEquals(1048576L, index.lastApplied()?.value)
            assertTrue(index.folders().isEmpty())
            assertTrue(index.snapshots().isEmpty())
            // Version 5: operations applied are remembered (audit 1, M4); version 6: with the message each
            // came in, and past a new content, so a snapshot load or a rescan does not forget them (audit 2, N2).
            val op = me.esdex.coffer.core.crypto.Id.of(ByteArray(16) { 7 })
            assertEquals(null, index.opAppliedFrom(op))
            index.markOpApplied(op, me.esdex.coffer.core.storage.MessageId(2097152))
            index.replaceContent(IndexContent.EMPTY)
            assertEquals(2097152L, index.opAppliedFrom(op)?.value)
            // Version 7: operations queued for Telegram, kept through a new content like uploads (2026-11-19).
            index.putOps("batch", "CREATE_FOLDERS", listOf(OpMessage("first", 9), OpMessage("second", 2)))
            index.replaceContent(IndexContent.EMPTY)
            assertEquals(listOf("first", "second"), index.ops().map { it.caption })
            // Version 8: how many operations each message holds, packs of SPEC §4.6 (2026-11-19).
            assertEquals(listOf(9, 2), index.ops().map { it.opCount })
            assertEquals(listOf(11, 11), index.ops().map { it.batchSize })
            // Version 9: deletes queued too, with the parts that go once the message is sent (2026-11-19).
            val gone = Id.of(ByteArray(16) { 7 })
            index.putOps("deletes", "DELETE", listOf(OpMessage("third", 2, listOf(gone), listOf(gone), listOf(MessageId(5), MessageId(6)))))
            val delete = index.ops().last()
            assertEquals(listOf(gone), delete.deletedFiles)
            assertEquals(listOf(gone), delete.deletedFolders)
            assertEquals(listOf(MessageId(5), MessageId(6)), delete.parts)
            assertEquals(false, delete.sent)
            index.markOpSent(delete.seq)
            assertEquals(listOf(false, false, true), index.ops().map { it.sent })
            assertEquals(emptyList<Id>(), index.ops().first().deletedFiles)
            // Version 10: an upload that replaces a file once it is whole (2026-11-19).
            index.putUpload(PendingUpload("replacing", byteArrayOf(1), null, emptyList(), null, replaces = gone))
            val replacing = index.uploads().single { it.uploadId == "replacing" }
            assertEquals(gone, replacing.replaces)
            index.removeUpload("replacing")
        }
        // Encrypted on disk now: not a plain SQLite file, and not the rows' bytes in the clear.
        assertFalse(RoomVaultIndex.isPlain(file))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("lastAppliedMessageId"))
        // And it opens again with the same key; the transfer queue outlives replaceContent.
        runBlocking { assertArrayEquals(byteArrayOf(4, 5), RoomVaultIndex(context, key, name).uploads().single().sealedRequest) }
        context.deleteDatabase(name)
    }

    @Test
    fun queuedOperationsAndUploadsGoThroughVersions7To11() {
        // Audit 3, Q3-12: rows of the queue itself, not only its columns, through every step since 7.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        helper.createDatabase(name, 7).use { db ->
            db.execSQL("INSERT INTO pending_ops (batch, batchSize, kind, caption) VALUES ('b1', 2, 'RENAME', 'coffer:v2:op:x')")
            db.execSQL("INSERT INTO uploads VALUES ('u7', ?, NULL, '', NULL)", arrayOf(byteArrayOf(7)))
        }
        helper.runMigrationsAndValidate(name, 11, true).close()
        val index = RoomVaultIndex(context, ByteArray(32) { 5 }, name)
        runBlocking {
            val op = index.ops().single()
            assertEquals("coffer:v2:op:x", op.caption)
            assertEquals("RENAME", op.kind)
            assertEquals(2, op.batchSize)
            // Columns that came later take their defaults: one operation, nothing deleted, not sent.
            assertEquals(1, op.opCount)
            assertEquals(emptyList<Id>(), op.deletedFiles)
            assertEquals(emptyList<Id>(), op.deletedFolders)
            assertEquals(emptyList<MessageId>(), op.parts)
            assertEquals(false, op.sent)
            val upload = index.uploads().single()
            assertEquals("u7", upload.uploadId)
            assertEquals(null, upload.replaces)
        }
        context.deleteDatabase(name)
    }
}
