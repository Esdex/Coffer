package me.esdex.coffer.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A key closed by one thread while another uses it (audit 1, L10). */
class SecretKeyTest {
    @Test
    fun closingWaitsForWorkStillUsingTheKey() {
        val original = ByteArray(32) { (it + 1).toByte() }
        val key = SecretKey.adopt(original.copyOf())
        val lent = CountDownLatch(1)
        var seenAtTheEnd: ByteArray? = null
        val worker = thread {
            key.withBytes { bytes ->
                lent.countDown()
                // The lock wipes the keys now, from another thread, while this is still encrypting with it.
                Thread.sleep(300)
                seenAtTheEnd = bytes.copyOf()
            }
        }
        assertTrue(lent.await(5, TimeUnit.SECONDS))
        key.close()
        worker.join()
        // Not a byte of the key turned to zero under the work.
        assertArrayEquals(original, seenAtTheEnd)
        assertTrue(key.isClosed)
        assertThrows(IllegalStateException::class.java) { key.withBytes { } }
    }
}
