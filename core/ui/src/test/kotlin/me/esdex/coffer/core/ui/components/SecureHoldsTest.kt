package me.esdex.coffer.core.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FLAG_SECURE between screens that slide over each other (audit 2, L-i). */
class SecureHoldsTest {
    @Test
    fun theScreenLeavingLastDoesNotTakeTheFlagFromTheOneComingIn() {
        // The recovery key, then the check of three words: the check comes in before the key has gone.
        val holds = SecureHolds()
        holds.acquire()
        holds.acquire()
        assertFalse(holds.release(keepAfter = false))
        // The last one out, with screenshots allowed in Settings: the flag goes.
        assertTrue(holds.release(keepAfter = false))
    }

    @Test
    fun theSettingKeepsTheFlagAfterTheLastScreen() {
        val holds = SecureHolds()
        holds.acquire()
        assertFalse(holds.release(keepAfter = true))
    }
}
