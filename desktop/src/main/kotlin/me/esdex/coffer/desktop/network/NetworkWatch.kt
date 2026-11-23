package me.esdex.coffer.desktop.network

import me.esdex.coffer.desktop.vault.log
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32

/**
 * Whether the computer has a way to the internet, from NetworkManager over the system bus (Esdex, 2026-11-23: the
 * tray tells when there is none). TDLib on its own does not notice: without network it goes on connecting, and
 * once the network is back it waits out its back-off, minutes after a long gap, as on the phone. So [onChange] is
 * told each time the network goes or comes, and once at the start if there is none.
 *
 * NetworkManager's State, which says whether there is a default route: with the phone's tethering cable out, Esdex's
 * computer read "connected locally" (50) while Tailscale and loopback stayed up, and its Connectivity read "limited",
 * not "none" as its documentation has it (recorded 2026-11-23). Back in, 40, 60, 70 within a second.
 *
 * Null from [start] without NetworkManager: TDLib then finds out for itself, later, and the tray says it is
 * connecting.
 */
class NetworkWatch private constructor(private val connection: DBusConnection, onChange: (Boolean) -> Unit) : AutoCloseable {
    @Volatile
    private var up: Boolean = true

    private val handler = connection.addSigHandler(Properties.PropertiesChanged::class.java) { signal ->
        if (signal.path != PATH || signal.interfaceName != BUS_NAME) return@addSigHandler
        val state = signal.propertiesChanged[STATE]?.value as? UInt32 ?: return@addSigHandler
        tell(available(state.toInt()), onChange)
    }

    init {
        val state = connection.getRemoteObject(BUS_NAME, PATH, Properties::class.java).Get<UInt32>(BUS_NAME, STATE)
        tell(available(state.toInt()), onChange, first = true)
    }

    @Synchronized
    private fun tell(available: Boolean, onChange: (Boolean) -> Unit, first: Boolean = false) {
        if (available == up && !first) return
        up = available
        log(if (available) "network up" else "no network")
        // Up at the start is what TDLib assumes already.
        if (!(first && available)) onChange(available)
    }

    override fun close() {
        runCatching { handler.close() }
        connection.close()
    }

    companion object {
        private const val BUS_NAME = "org.freedesktop.NetworkManager"
        private const val PATH = "/org/freedesktop/NetworkManager"
        private const val STATE = "State"

        /**
         * NetworkManager's NMState: a way out from connected to a site (60, a default route) up. Below it, from disabled
         * (10) to connected locally (50), there is none. Unknown (0) is no word either way, and TDLib is left to try.
         * Global connectivity (70) is not asked for: the check's own server may be the one out of reach.
         */
        fun available(state: Int): Boolean = state == 0 || state >= 60

        fun start(onChange: (Boolean) -> Unit): NetworkWatch? {
            val connection = runCatching { DBusConnectionBuilder.forSystemBus().build() }.getOrElse {
                log("no system bus, so no word of the network (${it.javaClass.simpleName})")
                return null
            }
            return try {
                NetworkWatch(connection, onChange)
            } catch (e: Exception) {
                log("no NetworkManager, so no word of the network (${e.javaClass.simpleName})")
                connection.close()
                null
            }
        }
    }
}
