package me.esdex.coffer.core.index

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
        // Validates the migrated schema against 7.json.
        helper.runMigrationsAndValidate(name, 7, true).close()

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
            index.putOps("batch", "CREATE_FOLDERS", listOf("first", "second"))
            index.replaceContent(IndexContent.EMPTY)
            assertEquals(listOf("first", "second"), index.ops().map { it.caption })
            assertEquals(listOf(2, 2), index.ops().map { it.batchSize })
        }
        // Encrypted on disk now: not a plain SQLite file, and not the rows' bytes in the clear.
        assertFalse(RoomVaultIndex.isPlain(file))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("lastAppliedMessageId"))
        // And it opens again with the same key; the transfer queue outlives replaceContent.
        runBlocking { assertArrayEquals(byteArrayOf(4, 5), RoomVaultIndex(context, key, name).uploads().single().sealedRequest) }
        context.deleteDatabase(name)
    }
}
