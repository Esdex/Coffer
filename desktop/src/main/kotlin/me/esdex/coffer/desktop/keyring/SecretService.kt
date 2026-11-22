package me.esdex.coffer.desktop.keyring

import me.esdex.coffer.desktop.dbus.Reply
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant

// The parts of the Secret Service API Coffer uses, as KWallet, GNOME Keyring and KeePassXC serve it:
// https://specifications.freedesktop.org/secret-service-spec/latest/
// Names and signatures here are the wire format: they follow the spec, not Kotlin's style.

/** org.freedesktop.Secret.Service at /org/freedesktop/secrets. */
@DBusInterfaceName("org.freedesktop.Secret.Service")
@Suppress("FunctionName")
interface SecretServiceApi : DBusInterface {
    /** The session's output, and its path. */
    fun OpenSession(algorithm: String, input: Variant<*>): Reply<Variant<*>, DBusPath>

    /** The matching items that are unlocked, and those that are locked. */
    fun SearchItems(attributes: Map<String, String>): Reply<List<DBusPath>, List<DBusPath>>

    /** What could be unlocked at once, and a prompt for the rest ("/" when none is needed). */
    fun Unlock(objects: List<DBusPath>): Reply<List<DBusPath>, DBusPath>

    fun GetSecrets(items: List<DBusPath>, session: DBusPath): Map<DBusPath, Secret>

    /** The collection behind an alias such as "default", or "/" when there is none. */
    fun ReadAlias(name: String): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
@Suppress("FunctionName")
interface SecretCollection : DBusInterface {
    /** The new item, and a prompt when the collection has to be unlocked first ("/" when not). */
    fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): Reply<DBusPath, DBusPath>
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
@Suppress("FunctionName")
interface SecretItem : DBusInterface {
    /** A prompt, or "/" when the item is gone already. */
    fun Delete(): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Session")
@Suppress("FunctionName")
interface SecretSession : DBusInterface {
    fun Close()
}

/** A dialog of the keyring's own, for the user to unlock it. */
@DBusInterfaceName("org.freedesktop.Secret.Prompt")
@Suppress("FunctionName")
interface SecretPrompt : DBusInterface {
    fun Prompt(windowId: String)

    class Completed(path: String, val dismissed: Boolean, val result: Variant<*>) : DBusSignal(path, dismissed, result)
}

/** (oayays): a secret as it travels in a session; with the "plain" algorithm the parameters are empty. */
class Secret(
    @field:Position(0) @JvmField val session: DBusPath,
    @field:Position(1) @JvmField val parameters: ByteArray,
    @field:Position(2) @JvmField val value: ByteArray,
    @field:Position(3) @JvmField val contentType: String,
) : Struct()
