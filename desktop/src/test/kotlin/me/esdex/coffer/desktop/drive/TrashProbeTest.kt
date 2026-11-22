package me.esdex.coffer.desktop.drive

import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.IndexedFile
import me.esdex.coffer.core.sync.VaultTree
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What KDE's trash does with a file on the drive (Esdex, 2026-11-22: no trash there): it must not copy it,
 * decrypted, into the home folder's trash. Runs KIO itself, as Dolphin's "Move to Trash" does, so only on
 * request: ./gradlew :desktop:test -Pcoffer.trashProbe --tests '*TrashProbe*'
 * A copy that does land in the trash is removed again, and the test fails.
 */
class TrashProbeTest {
    @Test
    fun movingToTheTrashLeavesNoCopyInTheHomeTrash() {
        assumeTrue("set -Pcoffer.trashProbe to run", System.getProperty("coffer.trashProbe") == "true")
        val name = "coffer-trash-probe-${UUID.randomUUID()}.txt"
        val fileId = Id.of(ByteArray(16) { 9 })
        val files = mutableMapOf(fileId to meta(name))
        val folders = mutableMapOf<Id, LocalFolder>()
        var tree = build(files, folders)
        val edits = object : DriveEdits {
            override suspend fun createFolder(parentId: Id?, name: String) {
                folders[Id.of(ByteArray(16) { (folders.size + 10).toByte() })] = LocalFolder(name, parentId)
                tree = build(files, folders)
            }

            override suspend fun move(entry: DriveTree.Entry, from: DriveTree.Folder, shownName: String, to: DriveTree.Folder, name: String) = Unit

            override suspend fun delete(entry: DriveTree.Entry) {
                files.clear()
                tree = build(files, folders)
            }
        }
        val at = Files.createTempDirectory(Path.of("build").toAbsolutePath().also(Files::createDirectories), "trash-probe")
        val trash = Path.of(System.getProperty("user.home"), ".local/share/Trash")
        // Readable, so a copy into the home trash would go through and show.
        val probe = "probe\n".toByteArray()
        val open = { _: DriveTree.File ->
            OpenFile(read = { position, buffer, offset, length ->
                if (position >= probe.size) -1 else minOf(length, probe.size - position.toInt()).also { probe.copyInto(buffer, offset, position.toInt(), position.toInt() + it) }
            }, onClose = {})
        }
        val mounted = VaultDrive.mount(at, tree = { tree }, open = open, edits = { edits })
        try {
            val process = ProcessBuilder("kioclient", "--noninteractive", "--platform", "offscreen", "move", at.resolve(name).toUri().toString(), "trash:/")
                .redirectErrorStream(true).start()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            val said = process.inputStream.bufferedReader().readText()
            if (!finished) process.destroyForcibly()
            println("KIO exit: ${if (finished) process.exitValue() else "timed out"}; said: ${said.trim()}")
            println("on the drive afterwards: ${if (files.isEmpty()) "deleted" else "still there"}; folders made: ${folders.values.map { it.name }}")
            val copied = Files.exists(trash.resolve("files").resolve(name))
            if (copied) {
                Files.deleteIfExists(trash.resolve("files").resolve(name))
                Files.deleteIfExists(trash.resolve("info").resolve("$name.trashinfo"))
            }
            assertFalse("a decrypted copy went into the home trash", copied)
            assertFalse("a trash folder was made on the drive", folders.values.any { it.name.startsWith(".Trash") })
        } finally {
            mounted.close()
            Files.deleteIfExists(at)
        }
    }

    private fun build(files: Map<Id, FileMeta>, folders: Map<Id, LocalFolder>) =
        DriveTree.of(VaultTree(folders.toMap(), files.toMap()), files.keys.associateWith { IndexedFile(it, 6, 1, ByteArray(0), FileStatus.READY, emptyMap()) })

    private fun meta(name: String) = FileMeta(
        name, null, "text/plain", 1, 2,
        Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES)), Bytes.of(ByteArray(CofferFormat.WRAPPED_KEY_BYTES)),
    )
}
