package me.esdex.coffer.desktop.keyring

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CompletableFuture

class KeyringException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Small secrets by name; [Keyring] in the app, a map in tests. */
interface SecretStore {
    /** The secret stored under [name], or null if there is none. */
    fun read(name: String): ByteArray?

    /** Stores [value] under [name], replacing what was there. */
    fun write(name: String, label: String, value: ByteArray)

    /** Removes the secret stored under [name], if there is one. */
    fun delete(name: String)
}

/**
 * Coffer's secrets in the desktop's keyring, through the Secret Service API: KWallet, GNOME Keyring or
 * whichever serves org.freedesktop.secrets. Each secret is one item, found by its name. A locked keyring
 * shows its own unlock dialog, and the call waits for the user (Esdex, 2026-11-22: the keyring, never a
 * file beside the data).
 *
 * The session is "plain": secrets cross the session bus unencrypted, which only this user's processes
 * can listen to, and those can ask the keyring for them anyway.
 */
class Keyring private constructor(private val connection: DBusConnection) : SecretStore, AutoCloseable {
    private val service = remote<SecretServiceApi>(SERVICE_PATH)
    private val session: DBusPath = service.OpenSession("plain", Variant("")).second

    @Synchronized
    override fun read(name: String): ByteArray? = call {
        val found = service.SearchItems(attributes(name))
        val items = found.first + found.second
        if (items.isEmpty()) return@call null
        if (found.second.isNotEmpty()) unlock(found.second)
        service.GetSecrets(items.take(1), session).values.firstOrNull()?.value
    }

    /** In the default collection. */
    @Synchronized
    override fun write(name: String, label: String, value: ByteArray): Unit = call {
        val collection = service.ReadAlias(DEFAULT_COLLECTION)
        if (collection.path == NO_OBJECT) throw KeyringException("the keyring has no default collection")
        unlock(listOf(collection))
        val properties = mapOf(
            "org.freedesktop.Secret.Item.Label" to Variant("Coffer: $label"),
            "org.freedesktop.Secret.Item.Attributes" to Variant(attributes(name), "a{ss}"),
        )
        val created = remote<SecretCollection>(collection.path)
            .CreateItem(properties, Secret(session, ByteArray(0), value, "application/octet-stream"), true)
        if (created.second.path != NO_OBJECT) prompt(created.second)
    }

    @Synchronized
    override fun delete(name: String): Unit = call {
        val found = service.SearchItems(attributes(name))
        if (found.second.isNotEmpty()) unlock(found.second)
        for (item in found.first + found.second) {
            val prompt = remote<SecretItem>(item.path).Delete()
            if (prompt.path != NO_OBJECT) prompt(prompt)
        }
    }

    override fun close() {
        runCatching { remote<SecretSession>(session.path).Close() }
        connection.close()
    }

    private fun unlock(objects: List<DBusPath>) {
        val reply = service.Unlock(objects)
        if (reply.second.path != NO_OBJECT) prompt(reply.second)
    }

    /** Shows the keyring's own dialog and waits until the user has answered it. */
    private fun prompt(path: DBusPath) {
        val answered = CompletableFuture<Boolean>()
        connection.addSigHandler(SecretPrompt.Completed::class.java) { signal ->
            if (signal.path == path.path) answered.complete(signal.dismissed)
        }.use {
            remote<SecretPrompt>(path.path).Prompt("")
            if (answered.get()) throw KeyringException("the keyring was not unlocked")
        }
    }

    private inline fun <reified T : DBusInterface> remote(path: String): T =
        connection.getRemoteObject(BUS_NAME, path, T::class.java)

    /** D-Bus errors as [KeyringException], so callers handle one kind. */
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: KeyringException) {
        throw e
    } catch (e: Exception) {
        throw KeyringException("the keyring refused: ${e.message}", e)
    }

    companion object {
        private const val BUS_NAME = "org.freedesktop.secrets"
        private const val SERVICE_PATH = "/org/freedesktop/secrets"
        private const val DEFAULT_COLLECTION = "default"
        private const val NO_OBJECT = "/"

        /** How Coffer's items are told from others; secret-tool finds them by the same attributes. */
        fun attributes(name: String) = mapOf("application" to "me.esdex.coffer", "secret" to name)

        /** Connects to the session's keyring. */
        fun open(): Keyring {
            val connection = try {
                DBusConnectionBuilder.forSessionBus().build()
            } catch (e: Exception) {
                throw KeyringException("no session bus: ${e.message}", e)
            }
            return try {
                Keyring(connection)
            } catch (e: Exception) {
                connection.close()
                throw KeyringException("no keyring answers on the session bus: ${e.message}", e)
            }
        }
    }
}
