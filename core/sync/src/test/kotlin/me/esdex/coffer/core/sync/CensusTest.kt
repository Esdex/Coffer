package me.esdex.coffer.core.sync

import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Counting Saved Messages by kind; the captions are written out by hand, not made by the code under test. */
class CensusTest {

    // Three to a page, so counting has to follow the history past the first one.
    private val cloud = FakeCloud(pageCap = 3)

    private fun post(content: MessageContent) = cloud.postFromOutside(content)

    @Test
    fun everyKindIsCountedWithWhatTelegramStores() = runBlocking {
        post(MessageContent.Text("coffer:v1:vault:AAAA"))
        post(MessageContent.Blob("coffer:v1:blob:part0", "a.bin", 1_000))
        post(MessageContent.Blob("coffer:v1:blob:part1", "b.bin", 2_500))
        post(MessageContent.Text("coffer:v1:op:rename"))
        post(MessageContent.Text("coffer:v1:op:move-it"))
        post(MessageContent.Blob("coffer:v1:index:snap", "c.bin", 700))
        post(MessageContent.Text("a note to myself"))
        post(MessageContent.Other)
        post(MessageContent.Blob("holiday photo", "IMG_0001.jpg", 4_000_000))

        val census = cloud.device().census()

        assertEquals(Census.Tally(2, 3_500), census.parts)
        // "coffer:v1:op:rename" is 19 bytes, "coffer:v1:op:move-it" 20.
        assertEquals(Census.Tally(2, 39), census.operations)
        assertEquals(Census.Tally(1, 700), census.snapshots)
        assertEquals(Census.Tally(1, 20), census.headers)
        assertEquals(3, census.ownMessages)
        assertEquals(Census.Tally(6, 3_500 + 39 + 700 + 20), census.coffer)
    }

    @Test
    fun aPackOfOperationsCountsAsOperations() = runBlocking {
        post(MessageContent.Text("coffer:v3:ops:pack"))
        assertEquals(Census.Tally(1, 18), cloud.device().census().operations)
        assertEquals(0, cloud.device().census().ownMessages)
    }

    @Test
    fun aTextCountsItsBytesNotItsCharacters() = runBlocking {
        // "Ж" is two bytes in UTF-8.
        post(MessageContent.Text("coffer:v1:op:Ж"))
        assertEquals(Census.Tally(1, 15), cloud.device().census().operations)
    }

    @Test
    fun theUsersOwnLookalikesAreTheirs() = runBlocking {
        post(MessageContent.Text("coffer:v3:op:x"))
        post(MessageContent.Text("coffer:v2:ops:x"))
        post(MessageContent.Text("coffer:v1:unknown:x"))
        post(MessageContent.Text("coffer:v1:op"))
        post(MessageContent.Blob("Coffer:v1:blob:x", "x", 10))
        val census = cloud.device().census()
        assertEquals(5, census.ownMessages)
        assertEquals(Census.Tally(), census.coffer)
    }

    @Test
    fun progressFollowsThePages() = runBlocking {
        repeat(7) { post(MessageContent.Text("coffer:v1:op:$it")) }
        val seen = mutableListOf<Int>()
        val census = cloud.device().census { seen += it }
        assertEquals(listOf(3, 6, 7), seen)
        assertEquals(7, census.operations.count)
    }

    @Test
    fun anEmptyHistoryIsAllZero() = runBlocking {
        assertEquals(Census(Census.Tally(), Census.Tally(), Census.Tally(), Census.Tally(), 0), cloud.device().census())
    }
}
