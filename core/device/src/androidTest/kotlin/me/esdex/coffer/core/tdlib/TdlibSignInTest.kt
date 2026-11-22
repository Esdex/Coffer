package me.esdex.coffer.core.tdlib

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.tdlib.TestDc.nextState
import me.esdex.coffer.core.tdlib.TestDc.signInByPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The sign-in paths the UI drives, on Telegram's test servers. */
@RunWith(AndroidJUnit4::class)
class TdlibSignInTest {

    private suspend fun expectError(text: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $text")
        } catch (e: TdlibException) {
            assertEquals(text, e.message)
        }
    }

    @Test
    fun phoneCodeAndPassword() = runBlocking {
        val phone = TestDc.newPhoneNumber()
        val first = TestDc.newSession("signin-a")
        first.signInByPhone(phone)
        assertEquals(AuthState.Ready, first.state.value)
        assertTrue(first.accountName().isNotEmpty())
        first.setPassword("correct horse")

        val second = TestDc.newSession("signin-b")
        assertEquals(AuthState.WaitPhoneNumber, second.nextState(null))
        second.submitPhoneNumber(phone)
        val code = second.nextState(AuthState.WaitPhoneNumber)
        assertTrue("code state, got $code", code is AuthState.WaitCode)
        code as AuthState.WaitCode
        expectError("PHONE_CODE_INVALID") { second.submitCode(TestDc.wrongCodeFor(code)) }
        second.submitCode(TestDc.codeFor(code))
        val password = second.nextState(code)
        assertTrue("password state, got $password", password is AuthState.WaitPassword)
        expectError("PASSWORD_HASH_INVALID") { second.submitPassword("wrong horse") }
        second.submitPassword("correct horse")
        assertEquals(AuthState.Ready, second.nextState(password))

        second.logOut()
        assertEquals(AuthState.Closed, second.nextState(AuthState.Ready))
        first.logOut()
        assertEquals(AuthState.Closed, first.nextState(AuthState.Ready))
    }
}
