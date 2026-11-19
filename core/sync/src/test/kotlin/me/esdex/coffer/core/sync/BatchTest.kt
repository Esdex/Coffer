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
    fun aFloodWaitIsWaitedOutAndTheActionTriedAgain() = runTest {
        val here = device()
        val fileId = here.upload("a.txt", BytesSource("a".toByteArray()))
        val waits = mutableListOf<Int>()
        cloud.failNextCall(FloodWaitException(7))
        waitingOutFloods(onWait = { waits += it }) { here.deleter.delete(here.keys, fileId, now = 1) }
        assertEquals(listOf(7), waits)
        assertTrue(currentTime >= 7_000)
        assertNull(here.index.file(fileId))
        assertTrue(cloud.allMessages().none { it.content is MessageContent.Blob })
    }

    @Test
    fun anyOtherErrorIsThrown() = runTest {
        val here = device()
        val fileId = here.upload("a.txt", BytesSource("a".toByteArray()))
        cloud.failNextCall(StorageException("connection lost"))
        try {
            waitingOutFloods { here.deleter.delete(here.keys, fileId, now = 1) }
            fail("the delete should have failed")
        } catch (_: StorageException) {
        }
        assertEquals(listOf(fileId), here.index.files().map { it.fileId })
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
