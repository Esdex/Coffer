package me.esdex.coffer.desktop.tray

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.image.BufferedImage

/** A line of the tray menu: an action, or a separator between groups of them. */
sealed interface MenuEntry {
    data class Action(val label: String, val onClick: () -> Unit) : MenuEntry
    data object Separator : MenuEntry
}

/**
 * Coffer's icon in the tray, spoken to the desktop over D-Bus as a StatusNotifierItem with a dbusmenu.
 * Not java.awt.SystemTray, which Compose's Tray is made of: that is the X11 XEmbed tray, and on
 * Plasma under Wayland its icon shows through a proxy that passes neither clicks nor the tooltip
 * (seen on Esdex's Plasma 6.8, 2026-11-21).
 */
class KdeTray(
    private val title: String,
    icon: List<BufferedImage>,
    private val entries: List<MenuEntry>,
    private val onActivate: () -> Unit,
) : AutoCloseable {
    private val pixmaps = icon.map { it.toPixmap() }
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus().build()

    init {
        val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
        connection.requestBusName(name)
        connection.exportObject(ITEM_PATH, Item())
        connection.exportObject(MENU_PATH, Menu())
        connection.getRemoteObject("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", StatusNotifierWatcher::class.java)
            .RegisterStatusNotifierItem(name)
    }

    override fun close() = connection.close()

    private inner class Item : StatusNotifierItem {
        override fun getObjectPath() = ITEM_PATH
        override fun getCategory() = "ApplicationStatus"
        override fun getId() = "coffer"
        override fun getTitle() = title
        override fun getStatus() = "Active"
        override fun getWindowId() = 0
        override fun getIconName() = ""
        override fun getIconPixmap() = pixmaps
        override fun getToolTip() = ToolTip("", pixmaps, title, "")
        override fun getItemIsMenu() = false
        override fun getMenu() = DBusPath(MENU_PATH)
        override fun Activate(x: Int, y: Int) = onActivate()
        override fun SecondaryActivate(x: Int, y: Int) = Unit
        override fun ContextMenu(x: Int, y: Int) = Unit
        override fun Scroll(delta: Int, orientation: String) = Unit
    }

    /** A flat menu: the root is id 0, the entries 1 and on in order. It never changes, so revision 1 always. */
    private inner class Menu : DBusMenu {
        override fun getObjectPath() = MENU_PATH
        override fun getVersion() = UInt32(3)
        override fun getTextDirection() = "ltr"
        override fun getStatus() = "normal"
        override fun getIconThemePath() = emptyList<String>()

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Reply<UInt32, LayoutItem> {
            val layout = if (parentId == 0) root() else item(parentId)
            return Reply(UInt32(1), layout)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>) =
            ids.filter { it in 0..entries.size }.map { ItemProperties(it, properties(it)) }

        override fun GetProperty(id: Int, name: String): Variant<*> = properties(id).getValue(name)

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId == "clicked") (entries.getOrNull(id - 1) as? MenuEntry.Action)?.onClick?.invoke()
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return events.map { it.id }.filterNot { it in 0..entries.size }
        }

        override fun AboutToShow(id: Int) = false

        override fun AboutToShowGroup(ids: List<Int>) = Reply(emptyList<Int>(), ids.filterNot { it in 0..entries.size })

        private fun root() = LayoutItem(
            0,
            properties(0),
            entries.indices.map { Variant(item(it + 1), LAYOUT_SIGNATURE) },
        )

        private fun item(id: Int) = LayoutItem(id, properties(id), emptyList())

        private fun properties(id: Int): Map<String, Variant<*>> = when (val entry = entries.getOrNull(id - 1)) {
            null -> mapOf("children-display" to Variant("submenu"))
            is MenuEntry.Action -> mapOf("label" to Variant(entry.label))
            MenuEntry.Separator -> mapOf("type" to Variant("separator"))
        }
    }

    private companion object {
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
    }
}

/** ARGB32 in network byte order, as the StatusNotifierItem spec asks. */
private fun BufferedImage.toPixmap(): Pixmap {
    val bytes = ByteArray(width * height * 4)
    var i = 0
    for (y in 0 until height) for (x in 0 until width) {
        val p = getRGB(x, y)
        bytes[i++] = (p ushr 24).toByte()
        bytes[i++] = (p ushr 16).toByte()
        bytes[i++] = (p ushr 8).toByte()
        bytes[i++] = p.toByte()
    }
    return Pixmap(width, height, bytes)
}
