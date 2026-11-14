package me.esdex.coffer.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Small files sealed on the phone (audit 1, L9, L14). */
class SealedTextFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** Stands in for the Keystore: XOR with a key byte per purpose, plus a check byte. Not crypto, a test double. */
    private class Xor(private val key: Int) : DeviceSealer {
        override fun seal(plain: ByteArray, purpose: String) = byteArrayOf(check(purpose)) + plain.map { (it.toInt() xor key).toByte() }
        override fun open(sealed: ByteArray, purpose: String): ByteArray? {
            if (sealed.isEmpty() || sealed[0] != check(purpose)) return null
            return sealed.drop(1).map { (it.toInt() xor key).toByte() }.toByteArray()
        }
        private fun check(purpose: String) = (purpose.hashCode() xor key).toByte()
    }

    private val secret = "12345 coffer:v2:vault:headerheaderheader\n"

    @Test
    fun whatIsWrittenIsSealedAndReadsBack() {
        val file = temp.newFile("headers.txt")
        SealedTextFile(file, Xor(0x5A), "headers").write(secret)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("coffer:"))
        assertEquals(secret, SealedTextFile(file, Xor(0x5A), "headers").read())
    }

    @Test
    fun aFileWrittenInTheClearByAnOlderVersionIsReadThenSealed() {
        val file = temp.newFile("headers.txt").apply { writeText(secret) }
        val sealed = SealedTextFile(file, Xor(0x5A), "headers")
        assertEquals(secret, sealed.read())
        // Sealed by the reading itself, not left for the next change.
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("coffer:"))
        assertEquals(secret, sealed.read())
    }

    @Test
    fun anotherKeyOrPurposeOpensNothing() {
        val file = temp.newFile("headers.txt")
        SealedTextFile(file, Xor(0x5A), "headers").write(secret)
        assertNull(SealedTextFile(file, Xor(0x33), "headers").read())
        assertNull(SealedTextFile(file, Xor(0x5A), "ledger").read())
    }
}
