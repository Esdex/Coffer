package me.esdex.coffer.core.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What only the fake offers: hooks the sync tests use to stage situations. */
class FakeCloudTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun blob(name: String) = temp.newFile("cfr_$name.bin").apply { writeBytes(ByteArray(10)) }

    @Test
    fun transfersAreLimitedPerDevice() = runTest {
        val cloud = FakeCloud()
        val device = cloud.device()
        val gate = CompletableDeferred<Unit>()
        cloud.onTransfer = { gate.await() }

        val sends = List(5) { async { device.sendBlob(blob("00000000000$it"), "c$it") } }
        repeat(10) { yield() }
        // The number from SPEC §6, not the constant: a test reading the constant would agree with any value.
        assertEquals(3, cloud.maxConcurrentTransfers)
        gate.complete(Unit)
        sends.awaitAll()
        assertEquals(3, cloud.maxConcurrentTransfers)
    }

    @Test
    fun uploadsAndDownloadsShareTheLimit() = runTest {
        val cloud = FakeCloud()
        val device = cloud.device()
        val stored = device.sendBlob(blob("aaaaaaaaaaaa"), "c")
        val gate = CompletableDeferred<Unit>()
        cloud.onTransfer = { gate.await() }

        val jobs = List(2) { async { device.sendBlob(blob("00000000000$it"), "c$it") } } +
            List(2) { async { device.openBlob(stored.id).close() } }
        repeat(10) { yield() }
        assertEquals(3, cloud.maxConcurrentTransfers)
        gate.complete(Unit)
        jobs.awaitAll()
    }

    @Test
    fun injectedFailureHitsOnlyTheNextCall() = runTest {
        val cloud = FakeCloud()
        val device = cloud.device()
        cloud.failNextCall(FloodWaitException(7))
        val error = assertThrows(FloodWaitException::class.java) { kotlinx.coroutines.runBlocking { device.sendText("a") } }
        assertEquals(7, error.seconds)
        device.sendText("b")
        assertEquals(listOf(MessageContent.Text("b")), cloud.allMessages().map { it.content })
    }

    @Test
    fun outsideChangesReachDevices() = runTest {
        val cloud = FakeCloud()
        val device = cloud.device()
        val photo = cloud.postFromOutside(MessageContent.Other)
        val text = device.sendText("coffer:v1:op:x")
        assertEquals(listOf(photo, text), device.historyBefore(null, 10).reversed())
        cloud.deleteFromOutside(listOf(text.id))
        assertEquals(listOf(photo), cloud.allMessages())
    }

    @Test
    fun pageCapShortensPagesBothWays() = runTest {
        val device = FakeCloud(pageCap = 2).device()
        val ids = List(5) { device.sendText("m$it").id }
        assertEquals(ids.subList(1, 3), device.historyAfter(ids[0], 10).map { it.id })
        assertEquals(listOf(ids[4], ids[3]), device.historyBefore(null, 10).map { it.id })
    }

    @Test
    fun idsHaveGaps() = runTest {
        val device = FakeCloud().device()
        val a = device.sendText("a").id.value
        val b = device.sendText("b").id.value
        assertEquals(1L shl 20, b - a)
    }
}
