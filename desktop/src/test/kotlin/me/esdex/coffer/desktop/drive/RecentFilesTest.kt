package me.esdex.coffer.desktop.drive

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import javax.xml.parsers.DocumentBuilderFactory

class RecentFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val drive = Path.of("/run/user/1000/coffer-dev")

    /** recently-used.xbel as GLib itself wrote it (PyGObject's GLib.BookmarkFile, 2.90), with seven files. */
    private fun fixture(): Path {
        val file = folder.root.toPath().resolve("recently-used.xbel")
        Files.write(file, checkNotNull(javaClass.getResourceAsStream("recently-used.xbel")).use { it.readBytes() })
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        return file
    }

    /** Each bookmark's href, by the JDK's own parser. */
    private fun hrefs(file: Path): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        val nodes = doc.getElementsByTagName("bookmark")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("href") }
    }

    @Test
    fun theDrivesFilesAndTheDriveItselfGoTheRestStays() {
        val file = fixture()
        assertEquals(3, RecentFiles(file, drive).sweep())
        assertEquals(
            listOf(
                "file:///home/someone/notes.txt",
                "file:///run/user/1000/coffer/Photos/c.jpg",
                "file:///run/user/1000/coffer-devices/d.pdf",
                "file:///home/someone/video.mp4",
            ),
            hrefs(file),
        )
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("nothing more to take", 0, RecentFiles(file, drive).sweep())
    }

    @Test
    fun kdesOrderOfAttributesToo() {
        // KDE rewrites the list with href after "added".
        val file = fixture()
        val text = Files.readString(file)
        val kde = Regex("""<bookmark href="([^"]*)" added="([^"]*)"""").replace(text) { "<bookmark added=\"${it.groupValues[2]}\" href=\"${it.groupValues[1]}\"" }
        Files.writeString(file, kde)
        assertEquals(3, RecentFiles(file, drive).sweep())
        assertEquals(4, hrefs(file).size)
    }

    @Test
    fun aMissingListIsLeftMissing() {
        val file = folder.root.toPath().resolve("recently-used.xbel")
        assertEquals(0, RecentFiles(file, drive).sweep())
        assertEquals(false, Files.exists(file))
    }

    @Test
    fun aListWrittenWhileWatchingIsSweptAtOnce() {
        val source = fixture()
        val dir = folder.newFolder("share").toPath()
        val file = dir.resolve("recently-used.xbel")
        RecentFiles(file, drive).use { recent ->
            recent.watch()
            // As GLib and KDE write it: a new copy renamed into place.
            val temp = dir.resolve(".recently-used.new")
            Files.copy(source, temp)
            Files.move(temp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            val deadline = System.currentTimeMillis() + 10_000
            while (hrefs(file).size != 4 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(4, hrefs(file).size)
        }
    }
}
