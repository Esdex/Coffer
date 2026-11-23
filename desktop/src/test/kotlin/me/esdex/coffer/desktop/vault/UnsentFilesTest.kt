package me.esdex.coffer.desktop.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UnsentFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun onlyAFileIsSomethingToLose() {
        val drafts = File(folder.root, "staging")
        assertFalse("no folder", unsentFilesIn(drafts))
        File(drafts, "parts").mkdirs()
        assertFalse("its empty folders, as they stay", unsentFilesIn(drafts))
        File(drafts, "parts/upload-1").mkdirs()
        File(drafts, "parts/upload-1/0").writeText("part")
        assertTrue("a prepared part", unsentFilesIn(drafts))
        File(drafts, "parts").deleteRecursively()
        File(drafts, "upload-2").writeText("copy")
        assertTrue("a copy", unsentFilesIn(drafts))
    }
}
