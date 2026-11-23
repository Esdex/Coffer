package me.esdex.coffer.desktop.tray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuIdsTest {
    private val lock = MenuEntry.Action("Lock") {}
    private val quit = MenuEntry.Action("Quit") {}

    private fun settings(dolphin: Boolean) = MenuEntry.Submenu(
        "Settings",
        listOf(MenuEntry.Toggle("Show the drive in Dolphin", dolphin) {}, MenuEntry.Toggle("Monochrome icon", false) {}),
    )

    @Test
    fun aSubmenuHoldsItsEntriesUnderItsOwnId() {
        val nodes = MenuIds().number(listOf(MenuEntry.Label("Up to date"), lock, settings(true), quit))
        val submenu = nodes[2]
        assertEquals(listOf("Show the drive in Dolphin", "Monochrome icon"), submenu.children.map { (it.entry as MenuEntry.Toggle).label })
        val ids = (nodes + submenu.children).map { it.id }
        assertEquals("every id its own", ids.size, ids.toSet().size)
        assertTrue("0 is the root", 0 !in ids)
        assertEquals(submenu.children[1], MenuIds.find(nodes, submenu.children[1].id))
        assertNull(MenuIds.find(nodes, 999))
    }

    @Test
    fun idsStayWithWhatTheEntriesAreAsTheMenuChanges() {
        val ids = MenuIds()
        val before = ids.number(listOf(MenuEntry.Label("Up to date"), lock, settings(true), quit))
        val after = ids.number(listOf(MenuEntry.Label("Uploading 3 files"), MenuEntry.Label("2 more files waiting"), lock, settings(false), quit))
        assertEquals(before[1].id, after[2].id)
        assertEquals(before[2].id, after[3].id)
        assertEquals("a setting keeps its id when ticked", before[2].children[0].id, after[3].children[0].id)
        assertEquals(before[3].id, after[4].id)
        // The same label in another place is another entry.
        val outside = ids.number(listOf(MenuEntry.Toggle("Monochrome icon", false) {}))
        assertTrue(outside[0].id != after[3].children[1].id)
    }
}
