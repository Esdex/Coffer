package me.esdex.coffer.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actions on a selection of many files or folders (pre-release item 1). */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val master = MasterKeys.generate(sodium)
    private val cloud = FakeCloud()

    private fun device() = Device(cloud, keysOf(master), temp.newFolder())

    @Test
    fun aFloodWaitIsWaitedOutAndTheSameItemTriedAgain() = runTest {
        val here = device()
        val files = listOf("a", "b", "c").map { here.upload("$it.txt", BytesSource(it.toByteArray())) }
        val waits = mutableListOf<Int>()
        var done = 0
        eachWaitingOutFloods(files, onWait = { waits += it }) { fileId ->
            // Telegram asks for a pause just as the second delete goes out.
            if (done == 1 && waits.isEmpty()) cloud.failNextCall(FloodWaitException(7))
            here.deleter.delete(here.keys, fileId, now = 1)
            done++
        }
        assertEquals(listOf(7), waits)
        assertTrue(currentTime >= 7_000)
        files.forEach { assertNull(here.index.file(it)) }
        assertTrue(cloud.allMessages().none { it.content is MessageContent.Blob })
    }

    @Test
    fun anyOtherErrorEndsTheBatch() = runTest {
        val here = device()
        val files = listOf("a", "b", "c").map { here.upload("$it.txt", BytesSource(it.toByteArray())) }
        var tried = 0
        try {
            eachWaitingOutFloods(files) { fileId ->
                tried++
                if (tried == 2) cloud.failNextCall(StorageException("connection lost"))
                here.deleter.delete(here.keys, fileId, now = 1)
            }
            fail("the second delete should have failed")
        } catch (_: StorageException) {
        }
        assertEquals(2, tried)
        assertNull(here.index.file(files[0]))
        assertEquals(listOf(files[1], files[2]).toSet(), here.index.files().map { it.fileId }.toSet())
    }

    @Test
    fun foldersNeverGoIntoThemselvesOrBelow() = runTest {
        val here = device()
        val docs = here.ops.createFolder("Docs", null)
        val tax = here.ops.createFolder("Tax", docs)
        val photos = here.ops.createFolder("Photos", null)
        val music = here.ops.createFolder("Music", null)
        here.indexer.catchUp()
        val tree = here.tree()
        val moving = listOf(docs, photos)
        assertFalse(tree.canTake(docs, moving))
        assertFalse(tree.canTake(tax, moving))
        assertFalse(tree.canTake(photos, moving))
        assertTrue(tree.canTake(music, moving))
        assertTrue(tree.canTake(null, moving))
        // Files alone can go anywhere.
        assertTrue(tree.canTake(tax, emptyList()))
    }
}
