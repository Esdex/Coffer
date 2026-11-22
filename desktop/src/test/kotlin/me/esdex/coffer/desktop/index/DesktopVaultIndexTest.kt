package me.esdex.coffer.desktop.index

import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.PendingUpload
import me.esdex.coffer.core.index.RoomVaultIndex
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.index.VaultIndexContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

/**
 * The desktop's index keeps the [VaultIndexContract] through Room and [SqlCipherDriver], and its file is
 * encrypted: checked with the sqlite3 and sqlcipher command-line tools, which share none of this code.
 */
class DesktopVaultIndexTest : VaultIndexContract() {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val opened = mutableListOf<RoomVaultIndex>()

    private fun open(file: File, with: ByteArray = key) = desktopVaultIndex(file, with).also { opened += it }

    override fun newIndex(): VaultIndex = open(folder.newFile("index.db").also { it.delete() })

    @After
    fun closeAll() = opened.forEach { it.close() }

    private fun written(): File {
        val file = File(folder.root, "written.db")
        val index = open(file)
        runBlocking {
            index.putFile(A, 12345, 1, byteArrayOf(1), FileStatus.READY)
            index.putUpload(PendingUpload("visible-upload-id", byteArrayOf(2), null, emptyList(), null))
        }
        index.close()
        return file
    }

    @Test
    fun theFileIsNotPlainSqlite() {
        val file = written()
        val head = file.inputStream().use { it.readNBytes(16) }
        assertFalse("SQLite's plain header", head.contentEquals("SQLite format 3\u0000".toByteArray()))
        assertFalse("rows readable in the file", String(file.readBytes(), Charsets.ISO_8859_1).contains("visible-upload-id"))
    }

    @Test
    fun theSameKeyOpensItAgainAndAnotherDoesNot() {
        val file = written()
        assertEquals(12345L, runBlocking { open(file).file(A)?.plainSize })
        val other = ByteArray(32).also(SecureRandom()::nextBytes)
        try {
            runBlocking { open(file, other).files() }
            fail("opened with another key")
        } catch (e: Exception) {
            // SQLite's "file is not a database", however Room wraps it.
            assertTrue(generateSequence<Throwable>(e) { it.cause }.any { "not a database" in it.message.orEmpty() })
        }
    }

    @Test
    fun plainSqlite3CannotReadIt() {
        assumeTrue("sqlite3 not installed", tool("sqlite3"))
        val (code, out) = run("sqlite3", written().path, "SELECT count(*) FROM files;")
        assertNotEquals("sqlite3 read it: $out", 0, code)
        assertTrue(out, "not a database" in out)
    }

    @Test
    fun sqlcipherReadsItWithTheRawKey() {
        assumeTrue("sqlcipher not installed", tool("sqlcipher"))
        val hex = key.joinToString("") { "%02x".format(it) }
        val (code, out) = run("sqlcipher", written().path, "PRAGMA key = \"x'$hex'\";\nSELECT plainSize FROM files;\n")
        assertEquals(out, 0, code)
        assertTrue(out, out.lines().any { it.trim() == "12345" })
    }

    private fun tool(name: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, name).canExecute() }

    /** Runs [command] with [input] on standard input; the exit code and everything it printed. */
    private fun run(command: String, file: String, input: String): Pair<Int, String> {
        val process = ProcessBuilder(command, file).redirectErrorStream(true).start()
        process.outputStream.use { it.write(input.toByteArray()) }
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return process.waitFor() to out
    }
}
