package me.esdex.coffer.feature.browser.transfer

import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.sync.ConflictDecider
import me.esdex.coffer.core.sync.FileChoice
import me.esdex.coffer.core.sync.FolderChoice
import me.esdex.coffer.core.sync.Incoming
import me.esdex.coffer.core.sync.NameConflicts
import me.esdex.coffer.core.sync.VaultTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folders in and out of the vault keep their shape (pre-release item 2). */
class FolderCopyTest {

    private var next = 0
    private fun id(): Id = Id.of(ByteArray(16) { if (it == 0) (++next).toByte() else 0 })

    @Test
    fun aPhoneFolderComesInWithItsShapeParentsFirst() = runTest {
        val phone = PhoneEntry.Folder(
            "Trip",
            listOf(
                PhoneEntry.File("a.jpg", "content://a", "image/jpeg"),
                PhoneEntry.Folder("Day 1", listOf(PhoneEntry.File("b.jpg", "content://b", null), PhoneEntry.Folder("Empty", emptyList()))),
                PhoneEntry.File(".nomedia", "content://n", null),
            ),
        )
        val made = mutableListOf<Triple<String, Id?, Id>>()
        val root = id()
        val placed = NameConflicts(VaultTree(emptyMap(), emptyMap()), ConflictDecider.always(FileChoice.SKIP, FolderChoice.SKIP)).place(root, listOf(phone.incoming()))
        val files = bringIn(placed, root) { name, parent -> id().also { made += Triple(name, parent, it) } }

        // Written out by hand: Trip under the folder on screen, Day 1 in Trip, Empty in Day 1.
        assertEquals(listOf("Trip", "Day 1", "Empty"), made.map { it.first })
        val (trip, day1) = made[0].third to made[1].third
        assertEquals(root, made[0].second)
        assertEquals(trip, made[1].second)
        assertEquals(day1, made[2].second)
        assertEquals(
            listOf("a.jpg" to trip, "b.jpg" to day1, ".nomedia" to trip),
            files.map { it.file.name to it.folderId },
        )
    }

    @Test
    fun aPhoneFolderMergedIntoOneOfTheSameNameMakesOnlyWhatIsNew() = runTest {
        // The vault has Trip/ with a.jpg; the phone's Trip has a.jpg again, b.jpg and Day 1/.
        val trip = id()
        val old = id()
        val vault = VaultTree(
            mapOf(trip to LocalFolder("Trip", null)),
            mapOf(old to FileMeta("a.jpg", trip, "image/jpeg", 1, 1, Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES)), Bytes.of(ByteArray(CofferFormat.WRAPPED_KEY_BYTES)))),
        )
        val phone = PhoneEntry.Folder(
            "trip",
            listOf(
                PhoneEntry.File("a.jpg", "content://a", "image/jpeg"),
                PhoneEntry.File("b.jpg", "content://b", "image/jpeg"),
                PhoneEntry.Folder("Day 1", emptyList()),
            ),
        )
        val placed = NameConflicts(vault, ConflictDecider.always(FileChoice.REPLACE, FolderChoice.MERGE)).place(null, listOf(phone.incoming()))
        val made = mutableListOf<Pair<String, Id?>>()
        val files = bringIn(placed, null) { name, parent -> id().also { made += name to parent } }
        assertEquals(listOf("Day 1" to trip), made)
        assertEquals(listOf(Arriving(phone.children[0] as PhoneEntry.File, trip, "a.jpg", replaces = old), Arriving(phone.children[1] as PhoneEntry.File, trip, "b.jpg")), files)
    }

    @Test
    fun namesTooLongForTheVaultAreFittedBeforeTheyAreCompared() {
        val long = "x".repeat(300) + ".txt"
        val incoming = PhoneEntry.Folder("", listOf(PhoneEntry.File(long, "content://l", null))).incoming() as Incoming.Folder
        assertEquals(FOLDER_FALLBACK_NAME, incoming.name)
        val name = incoming.children.single().name
        assertEquals(255, FileNames.utf8Size(name))
        assertTrue(name.endsWith(".txt"))
    }

    private fun tree(): Pair<VaultTree, Map<String, Id>> {
        val ids = listOf("Docs", "Tax", "Old", "x.pdf", "y.pdf", "z.pdf", "half.bin").associateWith { id() }
        fun folder(parent: String?, name: String) = LocalFolder(name, parent?.let(ids::getValue))
        fun file(folder: String, name: String) = FileMeta(
            name, ids.getValue(folder), "application/pdf", 1, 1,
            Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES)), Bytes.of(ByteArray(CofferFormat.WRAPPED_KEY_BYTES)),
        )
        val tree = VaultTree(
            folders = mapOf(
                ids.getValue("Docs") to folder(null, "Docs"),
                ids.getValue("Tax") to folder("Docs", "Tax"),
                ids.getValue("Old") to folder("Tax", "Old"),
            ),
            files = mapOf(
                ids.getValue("x.pdf") to file("Docs", "x.pdf"),
                ids.getValue("y.pdf") to file("Tax", "y.pdf"),
                ids.getValue("z.pdf") to file("Old", "z.pdf"),
                ids.getValue("half.bin") to file("Tax", "half.bin"),
            ),
        )
        return tree to ids
    }

    @Test
    fun aVaultFolderGoesOutWithItsShapeAndLeavesOutWhatIsNotWhole() = runTest {
        val (tree, ids) = tree()
        val name = ids.entries.associate { (k, v) -> v to k }
        val saved = mutableListOf<String>()
        val count = saveOut(
            tree, ids.getValue("Docs"), into = "phone",
            ready = { it != ids.getValue("half.bin") },
            createFolder = { folder, into -> "$into/$folder" },
            saveFile = { fileId, into -> saved += "$into/${name.getValue(fileId)}"; true },
        )
        assertEquals(
            listOf("phone/Docs/x.pdf", "phone/Docs/Tax/y.pdf", "phone/Docs/Tax/Old/z.pdf"),
            saved,
        )
        assertEquals(SaveCount(started = 3, notReady = 1), count)
    }

    @Test
    fun aFolderThePhoneRefusesCountsEverythingInItAsFailed() = runTest {
        val (tree, ids) = tree()
        val saved = mutableListOf<Id>()
        val count = saveOut(
            tree, ids.getValue("Docs"), into = "phone",
            ready = { true },
            createFolder = { folder, into -> if (folder == "Tax") null else "$into/$folder" },
            saveFile = { fileId, _ -> saved += fileId; true },
        )
        // x.pdf in Docs is saved; Tax could not be made, so y.pdf, half.bin and z.pdf below it fail.
        assertEquals(listOf(ids.getValue("x.pdf")), saved)
        assertEquals(SaveCount(started = 1, failed = 3), count)
        assertTrue(count.notReady == 0)
    }
}
