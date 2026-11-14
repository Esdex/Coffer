package me.esdex.coffer.feature.browser.viewer.audio

import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who may connect to the music session as a Media3 controller, and what it sees (audit 1, M13; audit 2, N3). */
class AudioServiceTest {
    private val own = "me.esdex.coffer"
    private val media3 = 1_009_000_300
    private val legacy = MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION

    @Test
    fun anotherAppsMedia3ControllerIsTurnedAway() {
        assertFalse(AudioService.mayConnect("com.example.listener", 10_123, media3, own))
    }

    @Test
    fun whatIsNotCofferGetsTheControlsAndNothingAboutWhatPlays() {
        // Audit 2, N3: a controller names its own version, so one that says "platform" may be any app.
        // Player.Commands needs Android to build; what goes is checked here, the removal on a device.
        val hidden = AudioService.WHAT_PLAYS.toSet()
        for (command in listOf(Player.COMMAND_GET_METADATA, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_TRACKS)) {
            assertTrue(command in hidden)
        }
        for (command in listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_STOP)) {
            assertFalse(command in hidden)
        }
    }

    @Test
    fun cofferTheSystemAndPlatformControllersComeIn() {
        assertTrue(AudioService.mayConnect(own, 10_100, media3, own))
        assertTrue(AudioService.mayConnect("android", 1000, media3, own))
        // A platform controller sees the platform session, which shows only "Coffer" unless asked for.
        assertTrue(AudioService.mayConnect("com.android.systemui", 10_050, legacy, own))
    }
}
