package me.esdex.coffer.desktop

import me.esdex.coffer.core.tdlib.ApiCredentials
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.tdlib.TelegramSecrets
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** What the build wrote into the app (desktop/build.gradle.kts, buildInfo). */
object BuildInfo {
    private val values = Properties().apply {
        BuildInfo::class.java.getResourceAsStream("build.properties")?.use(::load)
    }

    val version: String = values.getProperty("VERSION").orEmpty()

    /** Coffer's own API keys; null in a build without them. */
    val apiCredentials: ApiCredentials? =
        ApiCredentials.parse(values.getProperty("API_ID").orEmpty(), values.getProperty("API_HASH").orEmpty())
}

/**
 * Where Coffer keeps its data: $XDG_DATA_HOME/coffer, by default ~/.local/share/coffer. For this user
 * alone, as an Android app's own files are: created with the umask it would be readable by the group.
 */
fun dataDir(): File {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: Path.of(System.getProperty("user.home"), ".local", "share").toString()
    val dir = Path.of(base, "coffer")
    Files.createDirectories(dir)
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    return dir.toFile()
}

/**
 * The app's [Telegram] on this computer: TDLib's data under [dataDir], its key in the keyring, and the
 * computer named in the account's list of devices as Android names the phone, by its maker and model.
 */
fun desktopTelegram(secrets: TelegramSecrets): Telegram = Telegram(
    root = File(dataDir(), "tdlib"),
    secrets = secrets,
    bundledCredentials = BuildInfo.apiCredentials,
    allowsOwnKeys = false,
    deviceModel = deviceModel(),
    systemVersion = systemVersion(),
    applicationVersion = BuildInfo.version,
)

/** "Maker Model" from the firmware's tables, as the phone gives "Manufacturer Model"; "PC" without them. */
private fun deviceModel(): String {
    fun dmi(name: String) = runCatching { Files.readString(Path.of("/sys/class/dmi/id", name)).trim() }.getOrNull()
        ?.takeIf { it.isNotEmpty() && !it.equals("To be filled by O.E.M.", ignoreCase = true) }
    return listOfNotNull(dmi("sys_vendor"), dmi("product_name")).joinToString(" ").ifEmpty { "PC" }
}

/** The distribution's own name for itself, such as "CachyOS"; the kernel when it has none. */
private fun systemVersion(): String {
    val osRelease = runCatching { Files.readAllLines(Path.of("/etc/os-release")) }.getOrDefault(emptyList())
    val pretty = osRelease.firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')
    return pretty?.takeIf { it.isNotBlank() } ?: "Linux ${System.getProperty("os.version")}"
}
