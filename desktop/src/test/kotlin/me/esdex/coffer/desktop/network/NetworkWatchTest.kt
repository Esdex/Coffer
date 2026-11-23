package me.esdex.coffer.desktop.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkWatchTest {
    @Test
    fun aWayOutTakesADefaultRoute() {
        // NMState, from libnm's nm-dbus-interface.h: unknown, disabled, disconnected, disconnecting, connecting,
        // connected local (the cable out, Tailscale still up), site, global.
        val states = listOf(0, 10, 20, 30, 40, 50, 60, 70)
        assertEquals(listOf(true, false, false, false, false, false, true, true), states.map(NetworkWatch::available))
    }
}
