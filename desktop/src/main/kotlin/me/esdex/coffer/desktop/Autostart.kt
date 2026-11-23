package me.esdex.coffer.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Coffer started with the session (Esdex, 2026-11-24: a setting, off by default), as the freedesktop autostart spec
 * has it: an entry in [dir], named [id].desktop, that starts [launcher]. The entry is the setting: one taken out or
 * turned off in Plasma's own Autostart settings is off here too. Plasma turns it off by writing Hidden=true into it
 * (seen 2026-11-24), GNOME by X-GNOME-Autostart-enabled=false. Only for a packaged Coffer, whose launcher jpackage
 * names; Coffer run from Gradle has none to start.
 */
class Autostart(private val dir: Path, private val id: String, private val title: String, val launcher: String?) {
    private val entry = dir.resolve("$id.desktop")
    private val enabledFlow = MutableStateFlow(launcher != null && on(entry))

    val available: Boolean get() = launcher != null

    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()

    @Synchronized
    fun set(on: Boolean) {
        if (on) {
            val path = checkNotNull(launcher) { "no launcher to start" }
            Files.createDirectories(dir)
            val temp = Files.createTempFile(dir, ".$id", ".desktop")
            try {
                Files.writeString(temp, entry(path))
                Files.move(temp, entry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
        } else {
            Files.deleteIfExists(entry)
        }
        enabledFlow.value = on
    }

    private fun entry(launcher: String) = """
        |[Desktop Entry]
        |Type=Application
        |Name=$title
        |Comment=Coffer in the tray from the start of the session
        |Exec=${quote(launcher)}
        |Icon=me.esdex.coffer
        |Terminal=false
        |X-GNOME-Autostart-enabled=true
        |""".trimMargin()

    companion object {
        /** Whether [entry] starts anything: there, and neither hidden nor turned off. */
        internal fun on(entry: Path): Boolean {
            val lines = runCatching { Files.readAllLines(entry) }.getOrNull() ?: return false
            var group = ""
            for (line in lines.map(String::trim)) {
                if (line.startsWith("[")) group = line
                if (group != "[Desktop Entry]") continue
                val (key, value) = line.split("=", limit = 2).takeIf { it.size == 2 }?.map(String::trim) ?: continue
                if (key == "Hidden" && value == "true") return false
                if (key == "X-GNOME-Autostart-enabled" && value == "false") return false
            }
            return true
        }

        /** $XDG_CONFIG_HOME/autostart, by default ~/.config/autostart. */
        fun dir(): Path {
            val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".config")
            return base.resolve("autostart")
        }

        /** The launcher of a packaged Coffer, as jpackage tells it; null when run from Gradle. */
        fun launcher(): String? = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }

        /**
         * An Exec argument as the desktop entry spec has it, in two layers: quoted, with ", `, $ and \ each after a \,
         * then written as a string value, which doubles every \. So a $ in the path is written \\$, a \ four of them.
         */
        internal fun quote(arg: String): String {
            val quoted = arg.replace(Regex("""[\\"`$]""")) { "\\" + it.value }
            return "\"" + quoted.replace("\\", "\\\\") + "\""
        }
    }
}
