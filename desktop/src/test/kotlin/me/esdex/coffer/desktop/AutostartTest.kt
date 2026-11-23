package me.esdex.coffer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AutostartTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val launcher = "/opt/coffer/bin/coffer"

    @Test
    fun onWritesTheEntryOffTakesItAndTheEntryIsTheSetting() {
        val dir = folder.root.toPath().resolve("autostart")
        val autostart = Autostart(dir, "me.esdex.coffer", "Coffer", launcher)
        assertFalse(autostart.enabled.value)
        autostart.set(true)
        val entry = dir.resolve("me.esdex.coffer.desktop")
        assertTrue(Files.readString(entry).contains("Exec=\"/opt/coffer/bin/coffer\"\n"))
        assertTrue("read back at the next start", Autostart(dir, "me.esdex.coffer", "Coffer", launcher).enabled.value)
        // Taken out in Plasma's own settings.
        Files.delete(entry)
        assertFalse(Autostart(dir, "me.esdex.coffer", "Coffer", launcher).enabled.value)
        autostart.set(true)
        autostart.set(false)
        assertFalse(Files.exists(entry))
        assertFalse(autostart.enabled.value)
    }

    @Test
    fun turnedOffInPlasmaIsOffHereAndOnAgainUnhidesIt() {
        val dir = folder.root.toPath().resolve("autostart")
        Files.createDirectories(dir)
        val entry = dir.resolve("me.esdex.coffer.desktop")
        // As Plasma's Autostart settings rewrote Coffer's entry when turned off there (2026-11-24), its path changed.
        Files.writeString(
            entry,
            """
            |[Desktop Entry]
            |Comment=Coffer in the tray from the start of the session
            |Exec="/opt/coffer/bin/coffer"
            |Hidden=true
            |Icon=me.esdex.coffer
            |Name=Coffer
            |Terminal=false
            |Type=Application
            |X-GNOME-Autostart-enabled=true
            |""".trimMargin(),
        )
        val autostart = Autostart(dir, "me.esdex.coffer", "Coffer", launcher)
        assertFalse(autostart.enabled.value)
        autostart.set(true)
        assertTrue(Autostart(dir, "me.esdex.coffer", "Coffer", launcher).enabled.value)
        assertFalse(Files.readString(entry).contains("Hidden"))
        // GNOME's own way of turning it off.
        Files.writeString(entry, Files.readString(entry).replace("X-GNOME-Autostart-enabled=true", "X-GNOME-Autostart-enabled=false"))
        assertFalse(Autostart(dir, "me.esdex.coffer", "Coffer", launcher).enabled.value)
        // A Hidden in another group is not the entry's.
        Files.writeString(entry, "[Desktop Entry]\nExec=x\n[Desktop Action other]\nHidden=true\n")
        assertTrue(Autostart(dir, "me.esdex.coffer", "Coffer", launcher).enabled.value)
    }

    @Test
    fun runFromGradleThereIsNothingToStart() {
        val dir = folder.root.toPath().resolve("autostart")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("me.esdex.coffer.debug.desktop"), "[Desktop Entry]\n")
        val autostart = Autostart(dir, "me.esdex.coffer.debug", "Coffer Dev", launcher = null)
        assertFalse(autostart.available)
        assertFalse(autostart.enabled.value)
    }

    @Test
    fun aPathWithSpacesAndQuotesIsQuotedAsTheSpecHasIt() {
        // As the spec's own examples: a literal $ is \\$ in the file, a literal \ four of them.
        assertEquals("\"/home/a b/Coffer \\\\\"x\\\\\"/bin/coffer\"", Autostart.quote("/home/a b/Coffer \"x\"/bin/coffer"))
        assertEquals("\"/a/\\\\\$HOME/\\\\`b\\\\`\"", Autostart.quote("/a/\$HOME/`b`"))
        assertEquals("\"/a\\\\\\\\b\"", Autostart.quote("/a\\b"))
        assertEquals("\"/opt/coffer/bin/coffer\"", Autostart.quote("/opt/coffer/bin/coffer"))
    }

    @Test
    fun theEntryIsValidByFreedesktopsOwnValidator() {
        val validator = listOf("/usr/bin/desktop-file-validate", "/bin/desktop-file-validate").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("desktop-file-validate is not installed", validator != null)
        val dir = folder.root.toPath().resolve("autostart")
        Autostart(dir, "me.esdex.coffer", "Coffer", "/home/a b/Coffer \"x\"/bin/coffer").set(true)
        val process = ProcessBuilder(validator!!.path, dir.resolve("me.esdex.coffer.desktop").toString()).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assertEquals(out, 0, process.waitFor())
        assertFalse(out, out.contains("error"))
    }
}
