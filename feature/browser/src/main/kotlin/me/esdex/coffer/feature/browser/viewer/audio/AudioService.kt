package me.esdex.coffer.feature.browser.viewer.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Music in the background (pre-release item 5): the session that gives [AudioPlayback]'s player its
 * notification, its lock screen controls and the headset buttons. Nothing about what plays leaves Coffer
 * unless Settings, Player says so.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class AudioService : MediaSessionService() {

    @Inject lateinit var playback: AudioPlayback

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // A tap on the notification brings Coffer back on the player; behind its lock if the vault has locked meanwhile.
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = AudioPlayback.ACTION_SHOW_PLAYER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        session = MediaSession.Builder(this, NeutralPlayer(playback.player) { playback.prefs.value.showPlaying })
            .apply { open?.let(::setSessionActivity) }
            .setCallback(Gatekeeper(packageName))
            .build()
            // Added here, not waited for: the screen drives the player directly, so no controller ever
            // connects to hand the session to the service, and without one there is no notification.
            .also(::addSession)
    }

    /**
     * Only Coffer itself, Media3's notification inside it, and the system. The service has to be exported
     * for the system to reach it, so any app could otherwise connect, read what plays and drive it.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session.takeIf { controllerInfo.packageName == packageName || controllerInfo.uid == Process.SYSTEM_UID }

    /** Swiped out of recents with the music paused: nothing is left worth a notification. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playback.player.playWhenReady) {
            playback.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    /**
     * Who may connect as a Media3 controller, and what it may see. [onGetSession] guards the service, but a
     * platform controller, such as an app with notification access holds, can turn its session token into
     * a Media3 controller, and that comes in here: through the default, it would get the player's real tags
     * with each change and could drive the queue (audit 1, M13). It cannot be told from a real platform
     * controller, as it names its own version (audit 2, N3), so whatever is not Coffer itself gets the
     * player's controls and nothing about what plays: the platform session, which the lock screen and
     * notification listeners read, shows only "Coffer" unless asked for.
     */
    private class Gatekeeper(private val ownPackage: String) : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult = when {
            !mayConnect(controller.packageName, controller.uid, controller.controllerVersion, ownPackage) -> MediaSession.ConnectionResult.reject()
            controller.packageName == ownPackage -> super.onConnect(session, controller)
            else -> MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(controlsOnly(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS))
                .build()
        }
    }

    /**
     * What a session shows is mirrored to the notification, the lock screen, notification listeners and
     * every connected controller: all outside Coffer, past its lock and FLAG_SECURE. So unless asked for,
     * it shows "Coffer" and nothing else, whatever tags the player has read from the file (Arcanum's rule).
     */
    private class NeutralPlayer(player: Player, private val show: () -> Boolean) : ForwardingPlayer(player) {
        override fun getMediaMetadata(): MediaMetadata = if (show()) super.getMediaMetadata() else NEUTRAL
    }

    companion object {
        private val NEUTRAL: MediaMetadata = MediaMetadata.Builder().setTitle("Coffer").build()

        /**
         * Coffer, the system, and platform controllers (lock screen, headset, notification listeners), which
         * see the platform session's neutral metadata; never another app's Media3 controller (audit 1, M13).
         */
        internal fun mayConnect(packageName: String, uid: Int, controllerVersion: Int, ownPackage: String): Boolean =
            packageName == ownPackage || uid == SYSTEM_UID || controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION

        /**
         * [commands] without those that read what plays: its tags, the track and its length, the queue
         * and the tracks inside the file. Media3 sends each controller only what its commands allow.
         */
        internal fun controlsOnly(commands: Player.Commands): Player.Commands =
            commands.buildUpon().removeAll(*WHAT_PLAYS).build()

        internal val WHAT_PLAYS = intArrayOf(
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_TRACKS,
        )

        private const val SYSTEM_UID = Process.SYSTEM_UID
    }
}
