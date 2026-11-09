package me.esdex.coffer.feature.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.tdlib.Connection
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionTitleTest {

    private fun shown(steps: suspend (MutableStateFlow<Connection>, () -> Unit) -> Unit): List<Connection> {
        val seen = mutableListOf<Connection>()
        runTest {
            val link = MutableStateFlow(Connection.READY)
            val job = launch { link.ignoringBlips(1_000).collect { seen += it } }
            runCurrent()
            steps(link) { runCurrent() }
            job.cancel()
        }
        return seen
    }

    @Test
    fun aReconnectWithinTheGraceIsNeverShown() {
        val seen = shown { link, settle ->
            link.value = Connection.CONNECTING
            settle()
            link.value = Connection.READY
            settle()
        }
        assertEquals(listOf(Connection.READY, Connection.READY), seen)
    }

    @Test
    fun aLapseThatLastsIsShownAfterTheGraceAndClearsAtOnce() = runTest {
        val seen = mutableListOf<Connection>()
        val link = MutableStateFlow(Connection.READY)
        val job = launch { link.ignoringBlips(1_000).collect { seen += it } }
        runCurrent()
        link.value = Connection.WAITING_FOR_NETWORK
        advanceTimeBy(999)
        runCurrent()
        assertEquals(listOf(Connection.READY), seen)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(Connection.READY, Connection.WAITING_FOR_NETWORK), seen)
        link.value = Connection.READY
        runCurrent()
        assertEquals(Connection.READY, seen.last())
        job.cancel()
    }
}
