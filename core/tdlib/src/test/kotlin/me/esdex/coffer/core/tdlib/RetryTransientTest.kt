package me.esdex.coffer.core.tdlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a Keystore failure means the secret is gone, and when it is asked again (audit 1, L8). */
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
    fun aFailureThatLastsEndsAsBefore() {
        var calls = 0
        assertNull(retryTransient(4, {}, { it is Lost }) { calls++; throw IllegalStateException("still busy") })
        assertEquals(4, calls)
    }
}
