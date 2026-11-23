package me.esdex.coffer.desktop

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * One Coffer at a time (Esdex, 2026-11-23): TDLib's database takes one process, and Coffer started again from the
 * menu, while it already runs from the session's start, is someone asking to see it. The first holds [name] on the
 * session bus; a second start finds it held, asks the first to come forward ([onActivate]), and ends. Coffer and
 * Coffer Dev hold names of their own, so each runs beside the other.
 */
class SingleInstance private constructor(private val connection: DBusConnection) : AutoCloseable {
    private val handler = AtomicReference<() -> Unit>()

    /** What a second start asks for; until it is set, such a call does nothing. */
    var onActivate: () -> Unit
        get() = handler.get() ?: {}
        set(value) = handler.set(value)

    override fun close() = connection.close()

    /** The object a second start calls. Names are the wire format. */
    @DBusInterfaceName("me.esdex.coffer.App")
    @Suppress("FunctionName")
    interface App : DBusInterface {
        fun Activate()
    }

    private inner class Exported : App {
        override fun getObjectPath() = PATH
        override fun Activate() {
            handler.get()?.invoke()
        }
    }

    companion object {
        private const val PATH = "/App"

        /**
         * This start's claim to [name]: the claim if it is the first, null if another holds the name, which was asked to
         * come forward. Without a session bus there is no telling, and this start goes on as the first.
         */
        fun claim(name: String): Claim {
            // A connection of its own: the name belongs to the connection, and dbus-java shares one by default.
            val connection = runCatching { DBusConnectionBuilder.forSessionBus().withShared(false).build() }.getOrNull() ?: return Claim.NoBus
            val first = SingleInstance(connection)
            connection.exportObject(PATH, first.Exported())
            val claimed = runCatching { connection.requestBusName(name) }.isSuccess
            if (claimed) return Claim.First(first)
            val asked = runCatching { connection.getRemoteObject(name, PATH, App::class.java).Activate() }.isSuccess
            connection.close()
            return Claim.Running(asked)
        }
    }

    sealed interface Claim {
        data class First(val instance: SingleInstance) : Claim
        /** Another holds the name; [asked] whether it answered. */
        data class Running(val asked: Boolean) : Claim
        data object NoBus : Claim
    }
}
