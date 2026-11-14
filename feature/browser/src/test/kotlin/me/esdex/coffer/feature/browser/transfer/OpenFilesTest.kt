package me.esdex.coffer.feature.browser.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

/** A vault file's name as a name on disk, for a copy handed to another app (audit 1, L3). */
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
    fun anOrdinaryNameIsLeftAlone() {
        assertEquals("Lisbon trip.pdf", OpenFiles.safeName("Lisbon trip.pdf"))
        assertEquals("..hidden", OpenFiles.safeName("..hidden"))
        assertEquals("Ünïcødé — name.mp3", OpenFiles.safeName("Ünïcødé — name.mp3"))
    }
}
