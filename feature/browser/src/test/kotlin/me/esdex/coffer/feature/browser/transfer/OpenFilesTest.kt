package me.esdex.coffer.feature.browser.transfer

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpenFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun copy(name: String, writtenAt: Long): File {
        val folder = temp.newFolder(name)
        File(folder, "secret.pdf").writeText("plain")
        folder.setLastModified(writtenAt)
        return folder
    }

    @Test
    fun copiesGoTenMinutesAfterTheyWereWritten() {
        val now = 1_762_531_200_000
        copy("fresh", now - OpenFiles.LIFETIME_MILLIS + 1)
        copy("due", now - OpenFiles.LIFETIME_MILLIS)
        copy("old", now - 3 * OpenFiles.LIFETIME_MILLIS)
        OpenFiles.sweep(temp.root, now)
        assertEquals(listOf("fresh"), temp.root.list()!!.toList())
        assertEquals(10 * 60 * 1000L, OpenFiles.LIFETIME_MILLIS)
    }

    @Test
    fun aMissingFolderIsNothingToSweep() {
        OpenFiles.sweep(File(temp.root, "never-created"), 0)
    }
}

class ShareCopiesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun sweepKeepsOnlyWhatIsStillWanted() {
        val queued = temp.newFile("queued")
        val onScreen = temp.newFile("on-screen")
        temp.newFile("orphan")
        ShareInbox.sweep(temp.root, setOf(queued, File(onScreen.path)))
        assertEquals(setOf("queued", "on-screen"), temp.root.list()!!.toSet())
    }
}
