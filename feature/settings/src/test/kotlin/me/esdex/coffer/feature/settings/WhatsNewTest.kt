package me.esdex.coffer.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What's new (2026-11-20): the asset as shipped, and which versions a build shows. */
class WhatsNewTest {
    private val asset = File("src/main/assets/${WhatsNew.ASSET}").readText()
    private val whatsNew = WhatsNew.parse(asset)

    @Test
    fun theAssetIsWholeAndWrittenInKeyboardCharactersOnly() {
        assertTrue(whatsNew.versions.isNotEmpty())
        assertEquals(whatsNew.versions.size, whatsNew.versions.map { it.versionCode }.toSet().size)
        assertEquals(whatsNew.versions.size, whatsNew.versions.map { it.version }.toSet().size)
        for (version in whatsNew.versions) {
            assertTrue(version.version, version.entries.isNotEmpty())
            for (entry in version.entries) {
                assertTrue(entry.title, entry.type in WhatsNewEntry.TYPES)
                assertTrue(entry.title, entry.title.isNotBlank())
            }
        }
        // Public text in Esdex's voice: no em dashes, arrows or smart quotes.
        val odd = asset.filter { it.code > 0x7E }
        assertEquals("", odd)
    }

    @Test
    fun aBuildShowsItsOwnVersionThenTheOlderOnesNeverANewerOne() {
        val made = WhatsNew.parse(
            """{"versions":[{"version":"1.2.0","versionCode":3,"entries":[]},
               {"version":"1.0.0","versionCode":1,"entries":[]},{"version":"1.1.0","versionCode":2,"entries":[]}]}""",
        )
        assertEquals("1.1.0", made.current(2)?.version)
        assertEquals(listOf("1.0.0"), made.older(2).map { it.version })
        assertEquals(listOf("1.1.0", "1.0.0"), made.older(3).map { it.version })
        assertNull(made.current(9))
    }

    @Test
    fun entriesShowNewThenImprovementsThenSecurityThenFixesKeepingTheirOrder() {
        val entries = listOf("fix" to "f1", "new" to "n1", "security" to "s1", "improvement" to "i1", "new" to "n2", "odd" to "o1")
            .map { (type, title) -> WhatsNewEntry(type, title) }
        assertEquals(listOf("n1", "n2", "o1", "i1", "s1", "f1"), entries.inDisplayOrder().map { it.title })
    }
}
