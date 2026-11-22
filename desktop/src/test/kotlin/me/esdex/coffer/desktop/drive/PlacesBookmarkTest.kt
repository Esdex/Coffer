package me.esdex.coffer.desktop.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import javax.xml.parsers.DocumentBuilderFactory

/** Against a list of places shaped as KDE writes it, read back with the JDK's XML parser. */
class PlacesBookmarkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val original = """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE xbel>
        |<xbel xmlns:bookmark="http://www.freedesktop.org/standards/desktop-bookmarks" xmlns:kdepriv="http://www.kde.org/kdepriv" xmlns:mime="http://www.freedesktop.org/standards/shared-mime-info">
        |    <bookmark href="file:///home/someone">
        |        <title>Home</title>
        |        <info>
        |            <metadata owner="http://freedesktop.org">
        |                <bookmark:icon name="user-home"/>
        |            </metadata>
        |        </info>
        |    </bookmark>
        |    <bookmark href="file:///home/someone/.local/share/Cryptomator/mnt/s/">
        |        <title>s</title>
        |        <info>
        |            <metadata owner="http://freedesktop.org">
        |                <bookmark:icon name="drive-harddisk-encrypted"/>
        |            </metadata>
        |            <metadata owner="https://cryptomator.org">
        |                <id>0bfbfcba-8466-4d2d-a70d-2c4819039183</id>
        |            </metadata>
        |        </info>
        |    </bookmark>
        |</xbel>
        |""".trimMargin()

    private fun places(text: String? = original): Pair<Path, PlacesBookmark> {
        val file = folder.root.toPath().resolve("user-places.xbel")
        if (text != null) Files.writeString(file, text)
        return file to PlacesBookmark(file)
    }

    /** Each bookmark as its href and title, by the JDK's parser. */
    private fun bookmarks(file: Path): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        val nodes = doc.getElementsByTagName("bookmark")
        return (0 until nodes.length).map { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("href") to e.getElementsByTagName("title").item(0).textContent
        }
    }

    private fun ours(file: Path): Int {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        val metadata = doc.getElementsByTagName("metadata")
        return (0 until metadata.length).count { (metadata.item(it) as Element).getAttribute("owner") == PlacesBookmark.OWNER }
    }

    @Test
    fun theDriveIsAddedBesideWhatWasThere() {
        val (file, places) = places()
        places.add(Path.of("/run/user/1000/coffer"), "Coffer")
        assertEquals(
            listOf(
                "file:///home/someone" to "Home",
                "file:///home/someone/.local/share/Cryptomator/mnt/s/" to "s",
                "file:///run/user/1000/coffer/" to "Coffer",
            ),
            bookmarks(file),
        )
        assertEquals(1, ours(file))
    }

    @Test
    fun addedTwiceItIsThereOnce() {
        val (file, places) = places()
        places.add(Path.of("/run/user/1000/coffer"), "Coffer")
        places.add(Path.of("/run/user/1000/coffer"), "Coffer")
        assertEquals(1, ours(file))
        assertEquals(3, bookmarks(file).size)
    }

    @Test
    fun takenOutTheFileIsAsItWasByteForByte() {
        val (file, places) = places()
        places.add(Path.of("/run/user/1000/coffer"), "Coffer")
        places.remove()
        assertEquals(original, Files.readString(file))
    }

    @Test
    fun onlyCoffersBookmarkGoesWhereverItSits() {
        // Ours first, others after it, as Dolphin may reorder: none of theirs is taken with it.
        val (file, places) = places()
        places.add(Path.of("/run/user/1000/coffer"), "Coffer")
        val moved = Files.readString(file).let { text ->
            val ours = Regex("""(?s)[ \t]*<bookmark href="file:///run/user/1000/coffer/">.*?</bookmark>\n""").find(text)!!.value
            text.replace(ours, "").replace("    <bookmark href=\"file:///home/someone\">", ours + "    <bookmark href=\"file:///home/someone\">")
        }
        Files.writeString(file, moved)
        places.remove()
        assertEquals(listOf("Home", "s"), bookmarks(file).map { it.second })
        assertEquals(0, ours(file))
    }

    @Test
    fun aMissingListIsNotMade() {
        val (file, places) = places(text = null)
        places.add(Path.of("/run/user/1000/coffer"), "Coffer")
        places.remove()
        assertFalse(Files.exists(file))
    }

    @Test
    fun theFileKeepsItsPermissions() {
        val (file, places) = places()
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        places.add(Path.of("/run/user/1000/coffer"), "Coffer")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(listOf("user-places.xbel"), Files.list(folder.root.toPath()).use { s -> s.map { it.fileName.toString() }.toList() })
    }
}
