package me.esdex.coffer.core.tdlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/** When a Keystore failure means the secret is gone, and when it is asked again (audit 1, L8; audit 2, L-l). */
class RetryTransientTest {
    private class Lost : Exception()

    @Test
    fun aMomentaryFailureIsAskedAgain() {
        var calls = 0
        val pauses = mutableListOf<Int>()
        val result = retryTransient(4, { pauses += it }, { it is Lost }) {
            calls++
            if (calls < 3) throw IllegalStateException("keystore busy")
            byteArrayOf(1)
        }
        assertEquals(1, result!!.single().toInt())
        assertEquals(3, calls)
        assertEquals(listOf(0, 1), pauses)
    }

    @Test
    fun aLostSecretIsNotAskedAgain() {
        var calls = 0
        assertNull(retryTransient(4, {}, { it is Lost }) { calls++; throw Lost() })
        assertEquals(1, calls)
    }

    @Test
    fun aFailureThatLastsThrowsInsteadOfCountingAsLost() {
        // Audit 2, L-l: a null here wipes what the secret guards; a Keystore that does not answer is no reason.
        var calls = 0
        assertThrows(KeystoreUnavailableException::class.java) {
            retryTransient(4, {}, { it is Lost }) { calls++; throw IllegalStateException("still busy") }
        }
        assertEquals(4, calls)
    }

    @Test
    fun anUnreachableKeyIsNotALostOne() {
        // What keystore2 throws from getKey when the daemon fails: the reason rides along as the cause.
        assertFalse(isLostForGood(UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(RuntimeException("SYSTEM_ERROR")) }))
        assertFalse(isLostForGood(IllegalStateException("keystore busy")))
        // A key invalidated for good comes without a cause; a key that does not open the secret is lost too.
        assertTrue(isLostForGood(UnrecoverableKeyException("Key permanently invalidated")))
        assertTrue(isLostForGood(AEADBadTagException("mac check in GCM failed")))
    }
}
