package me.esdex.coffer.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * The desktop's own choices, in [file] in the data folder: each optional behaviour with its own setting, as on
 * the phone. Kept apart from the vault: signing out forgets the vault, not how the app looks.
 */
class DesktopSettings(private val file: File) {
    private val monochromeFlow = MutableStateFlow(read().getProperty(MONOCHROME_ICON) == "true")

    /** The tray's chest in the panel's colour, only what goes on in colour (Esdex, 2026-11-22); off by default. */
    val monochromeIcon: StateFlow<Boolean> = monochromeFlow.asStateFlow()

    @Synchronized
    fun setMonochromeIcon(on: Boolean) {
        write { it.setProperty(MONOCHROME_ICON, on.toString()) }
        monochromeFlow.value = on
    }

    private fun read() = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

    private fun write(change: (Properties) -> Unit) {
        val properties = read().also(change)
        file.parentFile.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { properties.store(it, null) }
        check(temp.renameTo(file)) { "could not save the settings" }
    }

    private companion object {
        const val MONOCHROME_ICON = "monochromeIcon"
    }
}
