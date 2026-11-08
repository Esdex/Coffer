package me.esdex.coffer.core.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the app looks. Defaults: follow the system, no AMOLED black, wallpaper colours on. */
data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = true,
)

/**
 * Where [Appearance] is kept: a small file of its own, so signing out of Telegram, which forgets the
 * vault and its preferences, leaves the look of the app alone.
 */
class AppearanceStore(private val file: File) {
    private val state = MutableStateFlow(read())
    val appearance: StateFlow<Appearance> = state.asStateFlow()

    @Synchronized
    fun update(change: (Appearance) -> Appearance) {
        val next = change(state.value)
        val props = Properties().apply {
            setProperty(THEME, next.themeMode.name)
            setProperty(AMOLED, next.amoled.toString())
            setProperty(DYNAMIC, next.dynamicColor.toString())
        }
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { props.store(it, null) }
        check(temp.renameTo(file)) { "could not store ${file.name}" }
        state.value = next
    }

    private fun read(): Appearance {
        if (!file.exists()) return Appearance()
        val props = Properties().apply { file.inputStream().use(::load) }
        val defaults = Appearance()
        return Appearance(
            themeMode = props.getProperty(THEME)?.let { name -> ThemeMode.entries.find { it.name == name } } ?: defaults.themeMode,
            amoled = props.getProperty(AMOLED)?.toBooleanStrictOrNull() ?: defaults.amoled,
            dynamicColor = props.getProperty(DYNAMIC)?.toBooleanStrictOrNull() ?: defaults.dynamicColor,
        )
    }

    private companion object {
        const val THEME = "themeMode"
        const val AMOLED = "amoled"
        const val DYNAMIC = "dynamicColor"
    }
}
