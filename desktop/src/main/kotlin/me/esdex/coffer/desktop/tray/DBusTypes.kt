package me.esdex.coffer.desktop.tray

import me.esdex.coffer.desktop.dbus.Reply
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusBoundProperty
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

// The two D-Bus interfaces of a tray icon, as Plasma and other StatusNotifierHosts speak them:
// https://www.freedesktop.org/wiki/Specifications/StatusNotifierItem/ and the dbusmenu of libdbusmenu.
// Names and signatures here are the wire format: they follow the specs, not Kotlin's style.

/** org.kde.StatusNotifierItem: the icon itself. */
@DBusInterfaceName("org.kde.StatusNotifierItem")
@Suppress("FunctionName")
interface StatusNotifierItem : DBusInterface {
    @DBusBoundProperty(name = "Category", access = Access.READ)
    fun getCategory(): String

    @DBusBoundProperty(name = "Id", access = Access.READ)
    fun getId(): String

    @DBusBoundProperty(name = "Title", access = Access.READ)
    fun getTitle(): String

    @DBusBoundProperty(name = "Status", access = Access.READ)
    fun getStatus(): String

    @DBusBoundProperty(name = "WindowId", access = Access.READ)
    fun getWindowId(): Int

    @DBusBoundProperty(name = "IconName", access = Access.READ)
    fun getIconName(): String

    @DBusBoundProperty(name = "IconPixmap", access = Access.READ, type = PixmapList::class)
    fun getIconPixmap(): List<Pixmap>

    @DBusBoundProperty(name = "ToolTip", access = Access.READ)
    fun getToolTip(): ToolTip

    @DBusBoundProperty(name = "ItemIsMenu", access = Access.READ)
    fun getItemIsMenu(): Boolean

    @DBusBoundProperty(name = "Menu", access = Access.READ)
    fun getMenu(): DBusPath

    fun Activate(x: Int, y: Int)

    fun SecondaryActivate(x: Int, y: Int)

    fun ContextMenu(x: Int, y: Int)

    fun Scroll(delta: Int, orientation: String)

    /** The tooltip changed: the host reads it again. */
    class NewToolTip(path: String) : DBusSignal(path)
}

/** org.kde.StatusNotifierWatcher: where an icon announces itself. */
@DBusInterfaceName("org.kde.StatusNotifierWatcher")
@Suppress("FunctionName")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

/** One size of the icon: ARGB32, bytes in network order. */
class Pixmap(
    @field:Position(0) @JvmField val width: Int,
    @field:Position(1) @JvmField val height: Int,
    @field:Position(2) @JvmField val argb: ByteArray,
) : Struct()

/** (sa(iiay)ss): an icon name, pixmaps, a title and a description. */
class ToolTip(
    @field:Position(0) @JvmField val iconName: String,
    @field:Position(1) @JvmField val pixmaps: List<Pixmap>,
    @field:Position(2) @JvmField val title: String,
    @field:Position(3) @JvmField val description: String,
) : Struct()

/** com.canonical.dbusmenu: the icon's menu, which the host draws itself. */
@DBusInterfaceName("com.canonical.dbusmenu")
@Suppress("FunctionName")
interface DBusMenu : DBusInterface {
    @DBusBoundProperty(name = "Version", access = Access.READ)
    fun getVersion(): UInt32

    @DBusBoundProperty(name = "TextDirection", access = Access.READ)
    fun getTextDirection(): String

    @DBusBoundProperty(name = "Status", access = Access.READ)
    fun getStatus(): String

    @DBusBoundProperty(name = "IconThemePath", access = Access.READ, type = StringList::class)
    fun getIconThemePath(): List<String>

    /** The layout's revision, and the layout. */
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Reply<UInt32, LayoutItem>

    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties>

    fun GetProperty(id: Int, name: String): Variant<*>

    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)

    fun EventGroup(events: List<MenuEvent>): List<Int>

    fun AboutToShow(id: Int): Boolean

    /** The ids to update, and the ids not found. */
    fun AboutToShowGroup(ids: List<Int>): Reply<List<Int>, List<Int>>

    /** The menu changed from [parent] down: the host asks for its layout again. */
    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

/** (ia{sv}av): an item, its properties, and its children as variants of the same. */
class LayoutItem(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
    @field:Position(2) @JvmField val children: List<Variant<*>>,
) : Struct()

/** (ia{sv}) */
class ItemProperties(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
) : Struct()

/** (isvu): one event of EventGroup. */
class MenuEvent(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val eventId: String,
    @field:Position(2) @JvmField val data: Variant<*>,
    @field:Position(3) @JvmField val timestamp: UInt32,
) : Struct()


// A bound property's type is read from its getter without generics, so a list would go out as av.
// These spell the element type out.
interface PixmapList : TypeRef<List<Pixmap>>

interface StringList : TypeRef<List<String>>

/** The signature of [LayoutItem], for the variants that carry the children. */
const val LAYOUT_SIGNATURE = "(ia{sv}av)"
