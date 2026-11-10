package me.esdex.coffer.core.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * The video player's extras, one switch each, as in Arcanum: the gestures change what a touch on the
 * video does and the controls add buttons, so each can be taken away by itself. All on by default.
 */
data class PlayerPrefs(
    val swipeVolume: Boolean = true,
    val swipeBrightness: Boolean = true,
    val holdForSpeed: Boolean = true,
    val lockButton: Boolean = true,
    val speedMenu: Boolean = true,
    val loop: Boolean = true,
)

/** Where [PlayerPrefs] is kept: a file of its own beside the appearance, kept over a sign-out. */
class PlayerStore(private val file: File) {
    private val state = MutableStateFlow(read())
    val prefs: StateFlow<PlayerPrefs> = state.asStateFlow()

    @Synchronized
    fun update(change: (PlayerPrefs) -> PlayerPrefs) {
        val next = change(state.value)
        val props = Properties()
        for ((key, get) in KEYS) props.setProperty(key, get(next).toString())
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { props.store(it, null) }
        check(temp.renameTo(file)) { "could not store ${file.name}" }
        state.value = next
    }

    private fun read(): PlayerPrefs {
        if (!file.exists()) return PlayerPrefs()
        val props = Properties().apply { file.inputStream().use(::load) }
        fun flag(key: String, default: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: default
        val d = PlayerPrefs()
        return PlayerPrefs(
            swipeVolume = flag(SWIPE_VOLUME, d.swipeVolume),
            swipeBrightness = flag(SWIPE_BRIGHTNESS, d.swipeBrightness),
            holdForSpeed = flag(HOLD_FOR_SPEED, d.holdForSpeed),
            lockButton = flag(LOCK_BUTTON, d.lockButton),
            speedMenu = flag(SPEED_MENU, d.speedMenu),
            loop = flag(LOOP, d.loop),
        )
    }

    private companion object {
        const val SWIPE_VOLUME = "swipeVolume"
        const val SWIPE_BRIGHTNESS = "swipeBrightness"
        const val HOLD_FOR_SPEED = "holdForSpeed"
        const val LOCK_BUTTON = "lockButton"
        const val SPEED_MENU = "speedMenu"
        const val LOOP = "loop"
        val KEYS: List<Pair<String, (PlayerPrefs) -> Boolean>> = listOf(
            SWIPE_VOLUME to { it.swipeVolume },
            SWIPE_BRIGHTNESS to { it.swipeBrightness },
            HOLD_FOR_SPEED to { it.holdForSpeed },
            LOCK_BUTTON to { it.lockButton },
            SPEED_MENU to { it.speedMenu },
            LOOP to { it.loop },
        )
    }
}
