package me.esdex.coffer.feature.browser.transfer

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.storage.DeviceSealer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** What another app may hand Coffer through the share sheet (audit 1, M10), and how it waits on the phone. */
class ShareInboxTest {
    private val own = "me.esdex.coffer"
    private val sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))

    /** Stands in for the Keystore: a seal that changes the bytes and remembers its purpose. */
    private val sealer = object : DeviceSealer {
        override fun seal(plain: ByteArray, purpose: String) = purpose.toByteArray() + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray, purpose: String): ByteArray? {
            val tag = purpose.toByteArray()
            if (sealed.size < tag.size || !sealed.copyOf(tag.size).contentEquals(tag)) return null
            return sealed.copyOfRange(tag.size, sealed.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Test
    fun aCopyIsEncryptedOnThePhoneAndReadsBackWhole() {
        // Audit 2, N4: the copy waits on the phone, before sign-in or unlock, for as long as it takes.
        val plain = "a shared document, waiting to be uploaded. ".repeat(50_000).toByteArray()
        val out = ByteArrayOutputStream()
        ShareInbox.sealing(out, sodium, sealer).use { it.write(plain) }
        val copy = out.toByteArray()
        assertFalse(String(copy, Charsets.ISO_8859_1).contains("a shared document"))
        assertArrayEquals(plain, ShareInbox.opening(ByteArrayInputStream(copy), sodium, sealer).use { it.readBytes() })
        // Another phone's Keystore, or a copy cut short, does not give it back.
        val elsewhere = object : DeviceSealer by sealer {
            override fun open(sealed: ByteArray, purpose: String): ByteArray? = null
        }
        assertThrows(IOException::class.java) { ShareInbox.opening(ByteArrayInputStream(copy), sodium, elsewhere) }
        assertThrows(IOException::class.java) {
            ShareInbox.opening(ByteArrayInputStream(copy.copyOf(copy.size - 1)), sodium, sealer).use { it.readBytes() }
        }
    }

    @Test
    fun onlyAnotherAppsContentIsTaken() {
        assertTrue(ShareInbox.accepts("content", "com.android.providers.media.documents", own))
        // A path would have Coffer read its own private files for whoever sent it.
        assertFalse(ShareInbox.accepts("file", null, own))
        assertFalse(ShareInbox.accepts("file", "", own))
        // Coffer's own provider, of this build or the personal one.
        assertFalse(ShareInbox.accepts("content", "me.esdex.coffer.files", own))
        assertFalse(ShareInbox.accepts("content", "me.esdex.coffer.personal.files", "me.esdex.coffer.personal"))
        assertFalse(ShareInbox.accepts("content", own, own))
        assertFalse(ShareInbox.accepts(null, "x", own))
        // A name that only begins like Coffer's is someone else's.
        assertTrue(ShareInbox.accepts("content", "me.esdex.coffeeshop.files", own))
    }

    @Test
    fun anEndlessStreamStopsBeforeThePhoneIsFull() {
        // Larger than the room there is; endless would hang the test instead of failing it.
        var left = 1024L * 1024 * 1024
        val endless = object : InputStream() {
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        var free = 200L * 1024 * 1024
        val out = object : java.io.OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) { free -= len }
        }
        assertThrows(ShareInbox.NoRoomException::class.java) { ShareInbox.copyWhileThereIsRoom(endless, out, { free }) {} }
        // Stopped with the margin still there, give or take one check's worth.
        assertTrue(free > 50L * 1024 * 1024)
    }

    @Test
    fun anOrdinaryCopyIsWhole() {
        val bytes = ByteArray(20 * 1024 * 1024) { it.toByte() }
        val out = ByteArrayOutputStream()
        var copied = 0L
        ShareInbox.copyWhileThereIsRoom(bytes.inputStream(), out, { Long.MAX_VALUE }) { copied += it }
        assertEquals(bytes.size.toLong(), copied)
        assertTrue(bytes.contentEquals(out.toByteArray()))
    }
}
