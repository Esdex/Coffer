package me.esdex.coffer.desktop.tray

import me.esdex.coffer.desktop.dbus.Reply
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap

/** A line of the tray menu: an action, a line of text, or a separator between groups of them. */
sealed interface MenuEntry {
    data class Action(val label: String, val onClick: () -> Unit) : MenuEntry
    data class Label(val text: String) : MenuEntry
    data object Separator : MenuEntry
}

/**
 * Coffer's icon in the tray, spoken to the desktop over D-Bus as a StatusNotifierItem with a dbusmenu.
 * Not java.awt.SystemTray, which Compose's Tray is made of: that is the X11 XEmbed tray, and on
 * Plasma under Wayland its icon shows through a proxy that passes neither clicks nor the tooltip
 * (seen on Esdex's Plasma 6.8, 2026-11-21).
 *
 * The menu and the tooltip change with [update]: the host is told and asks again. An action keeps its id
 * for as long as the tray lives, so a click on a menu drawn before a change still means what it said.
 */
class KdeTray(
    private val title: String,
    icon: List<BufferedImage>,
    private val onActivate: () -> Unit,
) : AutoCloseable {
    private val pixmaps = icon.map { it.toPixmap() }
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus().build()

    /** One look of the menu: its entries, each with its id, and the revision it is. */
    private class Shown(val revision: Long, val entries: List<Pair<Int, MenuEntry>>, val description: String)

    @Volatile
    private var shown = Shown(1, emptyList(), "")

    // Ids by what an entry is: an action by its label, a line or separator by its place. 0 is the root.
    private val ids = ConcurrentHashMap<String, Int>()

    private fun idOf(key: String) = ids.computeIfAbsent(key) { ids.size + 1 }

    init {
        val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
        connection.requestBusName(name)
        connection.exportObject(ITEM_PATH, Item())
        connection.exportObject(MENU_PATH, Menu())
        connection.getRemoteObject("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", StatusNotifierWatcher::class.java)
            .RegisterStatusNotifierItem(name)
    }

    /** Shows [entries] as the menu and [description] under the title in the tooltip. */
    @Synchronized
    fun update(entries: List<MenuEntry>, description: String) {
        val numbered = entries.mapIndexed { place, entry ->
            val key = when (entry) {
                is MenuEntry.Action -> "action:${entry.label}"
                is MenuEntry.Label -> "line:$place"
                MenuEntry.Separator -> "separator:$place"
            }
            idOf(key) to entry
        }
        val before = shown
        if (numbered == before.entries && description == before.description) return
        val now = Shown(before.revision + 1, numbered, description)
        shown = now
        if (numbered != before.entries) connection.sendMessage(DBusMenu.LayoutUpdated(MENU_PATH, UInt32(now.revision), 0))
        if (description != before.description) connection.sendMessage(StatusNotifierItem.NewToolTip(ITEM_PATH))
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
        override fun getToolTip() = ToolTip("", pixmaps, title, shown.description)
        override fun getItemIsMenu() = false
        override fun getMenu() = DBusPath(MENU_PATH)
        override fun Activate(x: Int, y: Int) = onActivate()
        override fun SecondaryActivate(x: Int, y: Int) = Unit
        override fun ContextMenu(x: Int, y: Int) = Unit
        override fun Scroll(delta: Int, orientation: String) = Unit
    }

    /** A flat menu: the root is id 0, its entries the ids [update] gave them. */
    private inner class Menu : DBusMenu {
        override fun getObjectPath() = MENU_PATH
        override fun getVersion() = UInt32(3)
        override fun getTextDirection() = "ltr"
        override fun getStatus() = "normal"
        override fun getIconThemePath() = emptyList<String>()

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Reply<UInt32, LayoutItem> {
            val current = shown
            val layout = if (parentId == 0) root(current) else item(current, parentId)
            return Reply(UInt32(current.revision), layout)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties> {
            val current = shown
            return ids.filter { it == 0 || entry(current, it) != null }.map { ItemProperties(it, properties(current, it)) }
        }

        override fun GetProperty(id: Int, name: String): Variant<*> = properties(shown, id).getValue(name)

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId == "clicked") (entry(shown, id) as? MenuEntry.Action)?.onClick?.invoke()
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            val current = shown
            return events.map { it.id }.filter { it != 0 && entry(current, it) == null }
        }

        override fun AboutToShow(id: Int) = false

        override fun AboutToShowGroup(ids: List<Int>): Reply<List<Int>, List<Int>> {
            val current = shown
            return Reply(emptyList(), ids.filter { it != 0 && entry(current, it) == null })
        }

        private fun entry(current: Shown, id: Int): MenuEntry? = current.entries.firstOrNull { it.first == id }?.second

        private fun root(current: Shown) = LayoutItem(
            0,
            properties(current, 0),
            current.entries.map { (id, _) -> Variant(item(current, id), LAYOUT_SIGNATURE) },
        )

        private fun item(current: Shown, id: Int) = LayoutItem(id, properties(current, id), emptyList())

        private fun properties(current: Shown, id: Int): Map<String, Variant<*>> = when (val entry = entry(current, id)) {
            null -> mapOf("children-display" to Variant("submenu"))
            is MenuEntry.Action -> mapOf("label" to Variant(entry.label))
            is MenuEntry.Label -> mapOf("label" to Variant(entry.text), "enabled" to Variant(false))
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
