package me.esdex.coffer.feature.browser.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Copies handed to another app: their names on disk (audit 1, L3) and how long they stay. */
class OpenFilesTest {
    @Test
    fun aNameStaysInItsFolder() {
        assertEquals("passwd", OpenFiles.safeName("../../shared_prefs/passwd"))
        assertEquals("evil.txt", OpenFiles.safeName("..\\..\\evil.txt"))
        assertEquals("file", OpenFiles.safeName(".."))
        assertEquals("file", OpenFiles.safeName("."))
        assertEquals("file", OpenFiles.safeName("dir/"))
        assertEquals("file", OpenFiles.safeName(""))
        assertEquals("ab.txt", OpenFiles.safeName("a\u0000b.txt"))
    }

    @Test
    fun aCopyIsAgedByItsLastWriteNotByWhenItsDownloadBegan() {
        // Audit 2, DATA2-10: a download longer than the lifetime lost its copy while it was still written.
        val dir = Files.createTempDirectory("open").toFile()
        val now = 100_000_000L
        val long = File(dir, "long").apply { mkdirs() }
        File(long, "video.mp4").apply { writeText("x"); setLastModified(now - 1_000) }
        long.setLastModified(now - 3_600_000)
        val old = File(dir, "old").apply { mkdirs() }
        File(old, "note.txt").apply { writeText("x"); setLastModified(now - 3_600_000) }
        old.setLastModified(now - 3_600_000)

        assertTrue(OpenFiles.sweep(dir, now, maxAgeMillis = 600_000))
        assertTrue(long.exists())
        assertFalse(old.exists())
        File(long, "video.mp4").setLastModified(now - 3_600_000)
        assertFalse(OpenFiles.sweep(dir, now, maxAgeMillis = 600_000))
        assertFalse(long.exists())
        dir.deleteRecursively()
    }

    @Test
    fun anOrdinaryNameIsLeftAlone() {
        assertEquals("Lisbon trip.pdf", OpenFiles.safeName("Lisbon trip.pdf"))
        assertEquals("..hidden", OpenFiles.safeName("..hidden"))
        assertEquals("Ünïcødé — name.mp3", OpenFiles.safeName("Ünïcødé — name.mp3"))
    }
}
