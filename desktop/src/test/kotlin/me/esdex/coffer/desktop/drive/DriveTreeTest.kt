package me.esdex.coffer.desktop.drive

import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.sync.VaultTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTreeTest {
    private val folders = mutableMapOf<Id, LocalFolder>()
    private val files = mutableMapOf<Id, FileMeta>()
    private val whole = mutableMapOf<Id, IndexedFile>()

    private fun id(n: Int) = Id.of(ByteArray(16) { if (it == 15) n.toByte() else 0 })

    private fun folder(n: Int, name: String, parent: Int? = null) = id(n).also { folders[it] = LocalFolder(name, parent?.let(::id)) }

    private fun file(n: Int, name: String, folder: Int? = null, modified: Long = 1000, size: Long = 10, isWhole: Boolean = true) = id(n).also {
        files[it] = FileMeta(
            name, folder?.let(::id), "application/octet-stream", 500, modified,
            Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES)), Bytes.of(ByteArray(CofferFormat.WRAPPED_KEY_BYTES)),
        )
        if (isWhole) whole[it] = IndexedFile(it, size, 1, ByteArray(0), FileStatus.READY, emptyMap())
    }

    private fun tree() = DriveTree.of(VaultTree(folders, files), whole)

    private fun DriveTree.children(path: String) = (this[path] as DriveTree.Folder).children.toSet()

    /** Every path, walked from the root: an oracle that holds whatever the tree's own map holds. */
    private fun DriveTree.walk(path: String = "/"): List<String> {
        val folder = this[path] as DriveTree.Folder
        return listOf(path) + folder.children.flatMap { child ->
            val childPath = if (path == "/") "/$child" else "$path/$child"
            if (this[childPath] is DriveTree.Folder) walk(childPath) else listOf(childPath)
        }
    }

    @Test
    fun foldersAndFilesSitWhereTheVaultHasThem() {
        folder(1, "Photos")
        folder(2, "2026", parent = 1)
        file(10, "a.jpg", folder = 2, size = 1234)
        file(11, "notes.txt")
        val tree = tree()
        assertEquals(setOf("Photos", "notes.txt"), tree.children("/"))
        assertEquals(setOf("2026"), tree.children("/Photos"))
        val a = tree["/Photos/2026/a.jpg"] as DriveTree.File
        assertEquals(id(10), a.fileId)
        assertEquals(1234L, a.size)
        assertEquals(listOf("/", "/Photos", "/Photos/2026", "/Photos/2026/a.jpg", "/notes.txt"), tree.walk().sorted())
        assertNull(tree["/missing"])
    }

    @Test
    fun onlyWholeFilesShow() {
        file(10, "done.txt")
        file(11, "uploading.txt", isWhole = false)
        assertEquals(setOf("done.txt"), tree().children("/"))
    }

    @Test
    fun aParentUnknownOrInALoopPutsTheFolderInTheRoot() {
        folder(1, "orphan", parent = 99)
        folder(2, "loopA", parent = 3)
        folder(3, "loopB", parent = 2)
        assertEquals(setOf("orphan", "loopA", "loopB"), tree().children("/"))
    }

    @Test
    fun aSecondThingWithTheSameNameIsNumberedInTheOrderOfIds() {
        file(12, "a.txt")
        file(10, "a.txt")
        file(11, "a.txt")
        val tree = tree()
        assertEquals(setOf("a.txt", "a (2).txt", "a (3).txt"), tree.children("/"))
        assertEquals(id(10), (tree["/a.txt"] as DriveTree.File).fileId)
        assertEquals(id(11), (tree["/a (2).txt"] as DriveTree.File).fileId)
        assertEquals(id(12), (tree["/a (3).txt"] as DriveTree.File).fileId)
    }

    @Test
    fun aNumberNeverTakesANameSomethingHasOfItsOwn() {
        file(10, "a.txt")
        file(11, "a.txt")
        file(5, "a (2).txt")
        folder(1, "a.txt")
        val tree = tree()
        assertEquals(4, tree.children("/").size)
        assertEquals(id(5), (tree["/a (2).txt"] as DriveTree.File).fileId)
        assertTrue(tree["/a.txt"] != null)
        assertEquals(setOf("a.txt", "a (2).txt", "a (3).txt", "a (4).txt"), tree.children("/"))
    }

    @Test
    fun namesAPathCannotHoldAreChanged() {
        file(10, "a/b.txt")
        folder(1, ".")
        folder(2, "..")
        assertEquals(setOf("a∕b.txt", "._", ".._"), tree().children("/"))
    }

    @Test
    fun longNamesFitLinuxAndKeepTheirExtension() {
        // 255 bytes, as long as a vault name may be: numbered, or with its slashes turned to "∕", it grows.
        val long = "x" + "é".repeat(125) + ".pdf"
        assertEquals(255, long.toByteArray().size)
        file(10, long)
        file(11, long)
        file(12, "/".repeat(255))
        for (name in tree().children("/")) {
            assertTrue("$name is ${name.toByteArray().size} bytes", name.toByteArray().size <= 255)
        }
        val pdfs = tree().children("/").filter { it.endsWith(".pdf") }
        assertEquals(2, pdfs.size)
        assertTrue(pdfs.any { it.endsWith(" (2).pdf") })
    }

    @Test
    fun aFolderIsAsNewAsTheNewestFileInIt() {
        folder(1, "A")
        folder(2, "B", parent = 1)
        file(10, "old", folder = 1, modified = 100)
        file(11, "new", folder = 2, modified = 900)
        val tree = tree()
        assertEquals(900L, tree["/A"]!!.modifiedAt)
        assertEquals(900L, tree["/A/B"]!!.modifiedAt)
        assertEquals(900L, tree["/"]!!.modifiedAt)
    }
}
