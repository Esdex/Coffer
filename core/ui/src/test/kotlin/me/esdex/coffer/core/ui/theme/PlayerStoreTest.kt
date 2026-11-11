package me.esdex.coffer.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayerStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun everyExtraIsOnByDefaultAndNothingIsShownOutside() {
        val prefs = PlayerStore(File(temp.root, "player.properties")).prefs.value
        assertEquals(PlayerPrefs(true, true, true, true, true, true, backgroundAudio = true, showPlaying = false, waveform = true), prefs)
    }

    @Test
    fun eachSwitchIsKeptByItself() {
        val file = File(temp.root, "nested/player.properties")
        val store = PlayerStore(file)
        store.update { it.copy(swipeBrightness = false) }
        store.update { it.copy(loop = false) }
        val expected = PlayerPrefs(swipeVolume = true, swipeBrightness = false, holdForSpeed = true, lockButton = true, speedMenu = true, loop = false)
        assertEquals(expected, store.prefs.value)
        assertEquals(expected, PlayerStore(file).prefs.value)
        // One switch off at a time, each on its own key: none follows another.
        for (off in listOf<(PlayerPrefs) -> PlayerPrefs>(
            { it.copy(swipeVolume = false) }, { it.copy(holdForSpeed = false) }, { it.copy(lockButton = false) }, { it.copy(speedMenu = false) },
            { it.copy(backgroundAudio = false) }, { it.copy(showPlaying = true) }, { it.copy(waveform = false) },
        )) {
            val one = File(temp.newFolder(), "player.properties")
            PlayerStore(one).update(off)
            assertEquals(off(PlayerPrefs()), PlayerStore(one).prefs.value)
        }
    }

    @Test
    fun aDamagedFileFallsBackValueByValue() {
        val file = File(temp.root, "player.properties")
        file.writeText("swipeVolume=false\nloop=perhaps\n")
        assertEquals(PlayerPrefs(swipeVolume = false), PlayerStore(file).prefs.value)
    }
}
