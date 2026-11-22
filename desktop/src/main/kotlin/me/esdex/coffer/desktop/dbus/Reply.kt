package me.esdex.coffer.desktop.dbus

import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.Position

/**
 * A D-Bus method's two out values. Generic on purpose: dbus-java takes the D-Bus types of a tuple from the
 * method's declared return type, and a tuple class with concrete fields is refused at the call.
 */
class Reply<A, B>(
    @field:Position(0) @JvmField val first: A,
    @field:Position(1) @JvmField val second: B,
) : Tuple()
