package me.esdex.coffer.desktop.tray

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.tdlib.Connection
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkTest {
    /** What the tray is told while [steps] run, each a state of the connection and how long it holds. */
    private fun told(vararg steps: Pair<Connection, Long>): List<TrayState.Link> {
        val told = mutableListOf<TrayState.Link>()
        runTest { tell(told, steps) }
        return told
    }

    private suspend fun TestScope.tell(told: MutableList<TrayState.Link>, steps: Array<out Pair<Connection, Long>>) {
        val connection = MutableStateFlow(steps.first().first)
        val job = launch { connection.asLink(patienceMs = 10_000).collect { told += it } }
        for ((state, ms) in steps) {
            connection.value = state
            runCurrent()
            advanceTimeBy(ms)
            runCurrent()
        }
        job.cancel()
    }

    @Test
    fun noNetworkIsToldAtOnce() {
        assertEquals(
            listOf(TrayState.Link.UP, TrayState.Link.WAITING_FOR_NETWORK, TrayState.Link.UP),
            told(Connection.READY to 1_000, Connection.WAITING_FOR_NETWORK to 1, Connection.READY to 1_000),
        )
    }

    @Test
    fun connectingAMomentIsNotTold() {
        // As at every start: connecting, updating, ready within seconds.
        assertEquals(
            listOf(TrayState.Link.UP),
            told(Connection.CONNECTING to 9_000, Connection.UPDATING to 2_000, Connection.READY to 1_000, Connection.CONNECTING to 9_999, Connection.READY to 1),
        )
    }

    @Test
    fun connectingThatLastsIsTold() {
        assertEquals(
            listOf(TrayState.Link.UP, TrayState.Link.CONNECTING, TrayState.Link.UP),
            told(Connection.CONNECTING to 10_001, Connection.READY to 1),
        )
    }

    @Test
    fun theNetworkBackIsNoLongerWaitedFor() {
        // Back from no network: connecting is not "waiting for network", nor yet worth telling.
        assertEquals(
            listOf(TrayState.Link.WAITING_FOR_NETWORK, TrayState.Link.UP),
            told(Connection.WAITING_FOR_NETWORK to 5_000, Connection.CONNECTING to 3_000),
        )
    }
}
