package me.esdex.coffer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

class DesktopSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theMonochromeIconIsOffUntilChosenAndStaysChosen() {
        val file = File(folder.root, "settings.properties")
        assertFalse(DesktopSettings(file).monochromeIcon.value)
        DesktopSettings(file).setMonochromeIcon(true)
        assertTrue("kept for the next start", DesktopSettings(file).monochromeIcon.value)
        // Read back with the JDK, as any tool would.
        assertEquals("true", Properties().apply { file.inputStream().use(::load) }.getProperty("monochromeIcon"))
        DesktopSettings(file).setMonochromeIcon(false)
        assertFalse(DesktopSettings(file).monochromeIcon.value)
    }
}
