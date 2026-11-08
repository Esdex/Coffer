package me.esdex.coffer.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppearanceStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun defaultsFollowTheSystemWithWallpaperColours() {
        val store = AppearanceStore(File(temp.root, "appearance.properties"))
        assertEquals(Appearance(ThemeMode.SYSTEM, amoled = false, dynamicColor = true), store.appearance.value)
    }

    @Test
    fun changesAreSeenAtOnceAndSurvive() {
        val file = File(temp.root, "nested/appearance.properties")
        val store = AppearanceStore(file)
        store.update { it.copy(themeMode = ThemeMode.DARK, amoled = true) }
        store.update { it.copy(dynamicColor = false) }
        val expected = Appearance(ThemeMode.DARK, amoled = true, dynamicColor = false)
        assertEquals(expected, store.appearance.value)
        assertEquals(expected, AppearanceStore(file).appearance.value)
    }

    @Test
    fun aDamagedFileFallsBackValueByValue() {
        val file = File(temp.root, "appearance.properties")
        file.writeText("themeMode=PURPLE\namoled=true\ndynamicColor=perhaps\n")
        assertEquals(Appearance(ThemeMode.SYSTEM, amoled = true, dynamicColor = true), AppearanceStore(file).appearance.value)
    }
}
