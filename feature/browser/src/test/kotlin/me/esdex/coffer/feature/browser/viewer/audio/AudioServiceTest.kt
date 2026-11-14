package me.esdex.coffer.feature.browser.viewer.audio

import androidx.media3.session.MediaSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who may connect to the music session as a Media3 controller (audit 1, M13). */
class AudioServiceTest {
    private val own = "me.esdex.coffer"
    private val media3 = 1_009_000_300
    private val legacy = MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION

    @Test
    fun anotherAppsMedia3ControllerIsTurnedAway() {
        assertFalse(AudioService.mayConnect("com.example.listener", 10_123, media3, own))
    }

    @Test
    fun cofferTheSystemAndPlatformControllersComeIn() {
        assertTrue(AudioService.mayConnect(own, 10_100, media3, own))
        assertTrue(AudioService.mayConnect("android", 1000, media3, own))
        // A platform controller sees the platform session, which shows only "Coffer" unless asked for.
        assertTrue(AudioService.mayConnect("com.android.systemui", 10_050, legacy, own))
    }
}
