package me.esdex.coffer.core.index

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        // Validates the migrated schema against 4.json.
        helper.runMigrationsAndValidate(name, 4, true).close()

        val index = RoomVaultIndex(context, name)
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
        }
        context.deleteDatabase(name)
    }
}
