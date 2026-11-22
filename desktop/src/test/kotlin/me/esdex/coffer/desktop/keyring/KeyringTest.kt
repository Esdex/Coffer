package me.esdex.coffer.desktop.keyring

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

/**
 * Against the real keyring of the session, checked with libsecret's secret-tool, which shares none of
 * this code. It writes and removes items named "Coffer: test", so it runs only when asked:
 * ./gradlew :desktop:test -Pcoffer.keyringTest
 */
class KeyringTest {
    private lateinit var keyring: Keyring

    @Before
    fun open() {
        assumeTrue("set -Pcoffer.keyringTest to run", System.getProperty("coffer.keyringTest") == "true")
        keyring = Keyring.open()
        NAMES.forEach(::secretToolClear)
    }

    @After
    fun close() {
        if (::keyring.isInitialized) {
            NAMES.forEach(::secretToolClear)
            keyring.close()
        }
    }

    @Test
    fun whatCofferWritesSecretToolReads() {
        val value = random()
        keyring.write(NAMES[0], "test", value)
        assertArrayEquals(value, secretToolLookup(NAMES[0]))
    }

    @Test
    fun whatSecretToolStoresCofferReads() {
        // secret-tool stores UTF-8 text only.
        val value = random().joinToString("") { "%02x".format(it) }.toByteArray()
        secretToolStore(NAMES[1], value)
        assertArrayEquals(value, keyring.read(NAMES[1]))
    }

    @Test
    fun aWriteReplacesInsteadOfAddingASecondItem() {
        keyring.write(NAMES[0], "test", random())
        val second = random()
        keyring.write(NAMES[0], "test", second)
        assertArrayEquals(second, keyring.read(NAMES[0]))
        assertArrayEquals(second, secretToolLookup(NAMES[0]))
    }

    @Test
    fun aDeletedSecretIsGoneForBoth() {
        keyring.write(NAMES[0], "test", random())
        keyring.delete(NAMES[0])
        assertNull(keyring.read(NAMES[0]))
        assertNull(secretToolLookup(NAMES[0]))
    }

    @Test
    fun namesDoNotMix() {
        val first = random()
        keyring.write(NAMES[0], "test", first)
        assertNull(keyring.read(NAMES[1]))
        keyring.write(NAMES[1], "test", random())
        assertArrayEquals(first, keyring.read(NAMES[0]))
        assertNotEquals(first.toList(), keyring.read(NAMES[1])!!.toList())
    }

    private fun random() = ByteArray(32).also(SecureRandom()::nextBytes)

    private fun attributes(name: String) = Keyring.attributes(name).flatMap { (k, v) -> listOf(k, v) }

    private fun secretTool(vararg args: String, input: ByteArray? = null): Pair<Int, ByteArray> {
        val process = ProcessBuilder(listOf("secret-tool") + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { if (input != null) it.write(input) }
        val out = process.inputStream.readBytes()
        return process.waitFor() to out
    }

    /** Null when secret-tool finds nothing (it exits with 1). */
    private fun secretToolLookup(name: String): ByteArray? =
        secretTool("lookup", *attributes(name).toTypedArray()).let { (code, out) -> if (code == 0) out else null }

    private fun secretToolStore(name: String, value: ByteArray) {
        val (code, _) = secretTool("store", "--label=Coffer: test", *attributes(name).toTypedArray(), input = value)
        check(code == 0) { "secret-tool store failed" }
    }

    private fun secretToolClear(name: String) {
        secretTool("clear", *attributes(name).toTypedArray())
    }

    private companion object {
        val NAMES = listOf("coffer-test-a", "coffer-test-b")
    }
}
