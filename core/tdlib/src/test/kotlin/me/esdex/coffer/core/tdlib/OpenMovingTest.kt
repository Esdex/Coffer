package me.esdex.coffer.core.tdlib

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException

class OpenMovingTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun aPathMovedAwayIsAskedForAgain() = runBlocking {
        // What TDLib does to a download that just completed: the temp path it answered with is gone.
        val gone = dir.root.resolve("temp/38").path
        val moved = dir.newFile("document.bin").apply { writeBytes(byteArrayOf(7, 8, 9)) }.path
        val answers = ArrayDeque(listOf(gone, moved))
        var asked = 0
        val input = openMoving {
            asked++
            answers.removeFirst()
        }
        assertEquals(7, input.use { it.read() })
        assertEquals(2, asked)
    }

    @Test
    fun aPathThatStaysGoneFailsAfterTheLastAttempt() {
        val gone = dir.root.resolve("temp/38").path
        var asked = 0
        assertThrows(FileNotFoundException::class.java) {
            runBlocking { openMoving(attempts = 3) { asked++; gone } }
        }
        assertEquals(3, asked)
    }
}
