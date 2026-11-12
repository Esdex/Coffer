package me.esdex.coffer.feature.onboarding

import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.CodeDelivery
import me.esdex.coffer.core.tdlib.TdlibException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign-in steps: which way a change of step slides, and what the code cells take. */
class SignInStepsTest {

    private val code = AuthState.WaitCode("+10000000000", CodeDelivery.TELEGRAM_APP, length = 5, canResend = false, resendAfterSeconds = 0)

    @Test
    fun goingOnSlidesForwardAndStartingOverSlidesBack() {
        // The way Telegram leads through a sign-in, in the order it does.
        val path = listOf(
            AuthState.Starting,
            AuthState.NeedApiCredentials,
            AuthState.WaitPhoneNumber,
            code,
            AuthState.WaitEmailAddress,
            AuthState.WaitEmailCode("a***@b.c", 6),
            AuthState.WaitPassword("hint", hasRecoveryEmail = false),
            AuthState.Ready,
        )
        path.zipWithNext().forEach { (from, to) -> assertTrue("$from to $to", order(to) > order(from)) }
        // Start over from the code or the QR: back to the phone number, a step back.
        assertTrue(order(AuthState.WaitPhoneNumber) < order(code))
        assertTrue(order(AuthState.WaitPhoneNumber) < order(AuthState.WaitQrConfirmation("tg://login?token=x")))
        // The QR code and the code from Telegram are two ways through the same step.
        assertEquals(order(code), order(AuthState.WaitQrConfirmation("tg://login?token=x")))
        // A dead end or a failure comes after every step it can follow.
        assertTrue(order(AuthState.Failed(TdlibException(500, "x"))) > order(AuthState.WaitPassword("", false)))
    }

    @Test
    fun theCellsTakeLettersAndDigitsUpToTheLength() {
        assertEquals("12345", CodeCells.clean("12345", 5))
        // Pasted from a message: spaces and dashes go, the rest is cut at the length.
        assertEquals("12345", CodeCells.clean("123-45 678", 5))
        assertEquals("ab1", CodeCells.clean(" a b 1 ", 6))
        assertEquals("", CodeCells.clean("--", 5))
    }
}
