package me.esdex.coffer.feature.browser.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** What another app may hand Coffer through the share sheet (audit 1, M10). */
class ShareInboxTest {
    private val own = "me.esdex.coffer"

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
