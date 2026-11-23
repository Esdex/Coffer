package me.esdex.coffer.desktop.drive

import me.esdex.coffer.desktop.vault.log
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import kotlin.concurrent.thread

/**
 * The drive's files taken back out of the desktop's lists of recent files (Esdex, 2026-11-23): each app that opens a
 * file from the drive puts its path there, and with it the names of the file and its folders, which the vault keeps
 * encrypted. Measured on Esdex's computer: 12 of the drive's paths among 300 recent files.
 *
 * Two lists: [xbel], the freedesktop one every KDE and GTK app writes, and KDE's activity database, asked over D-Bus
 * ([activities]) when there is one; it keeps nothing when Plasma is told not to remember documents. Each is swept
 * whenever the list changes, at the start and when the drive goes. What apps keep of their own (a player's playlist,
 * an editor's session, the clipboard's history) is theirs, and the docs say so.
 */
class RecentFiles(private val xbel: Path, drive: Path, private val activities: Activities? = null) : AutoCloseable {
    private val path = drive.toAbsolutePath().toString().trimEnd('/')

    // A bookmark whose href is the drive or under it, its attributes in either order: GLib writes href first, KDE
    // after "added". Never reaching past its own </bookmark>, so no other is taken.
    private val ours = Regex(
        """[ \t]*<bookmark\b[^>]*?\shref="${Regex.escape("file://$path")}(?:/[^"]*)?"[^>]*?(?:/>|>(?:(?!</bookmark>).)*?</bookmark>)[ \t]*\r?\n?""",
        RegexOption.DOT_MATCHES_ALL,
    )

    @Volatile
    private var watcher: WatchService? = null

    /** Takes the drive's paths out of both lists; how many it took out of [xbel]. */
    @Synchronized
    fun sweep(): Int {
        activities?.forget(path)
        val text = runCatching { Files.readString(xbel) }.getOrNull() ?: return 0
        val removed = ours.findAll(text).count()
        if (removed == 0) return 0
        write(ours.replace(text, ""))
        log("removed $removed of the drive's files from the recent files")
        return removed
    }

    /** Sweeps each time an app writes [xbel], until [close]. */
    fun watch() {
        val dir = xbel.parent ?: return
        val service = runCatching { FileSystems.getDefault().newWatchService().also { dir.register(it, ENTRY_CREATE, ENTRY_MODIFY) } }.getOrElse {
            log("cannot watch the recent files (${it.javaClass.simpleName})")
            return
        }
        watcher = service
        thread(name = "recent files", isDaemon = true) {
            try {
                while (true) {
                    val key = service.take()
                    // Written by renaming a new copy into place (GLib, KDE) or in place: either is a change of the list.
                    val changed = key.pollEvents().any { it.context()?.toString() == xbel.fileName.toString() }
                    if (changed) runCatching { sweep() }.onFailure { log("sweeping the recent files failed (${it.javaClass.simpleName})") }
                    if (!key.reset()) break
                }
            } catch (_: ClosedWatchServiceException) {
            } catch (_: InterruptedException) {
            }
        }
    }

    override fun close() {
        runCatching { watcher?.close() }
        activities?.close()
    }

    /** Written whole and swapped in, as GLib writes it, with the file's own permissions. */
    private fun write(text: String) {
        val temp = Files.createTempFile(xbel.parent, ".recently-used", ".xbel")
        try {
            Files.writeString(temp, text)
            runCatching { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(xbel)) }
            Files.move(temp, xbel, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** KDE's activity database of used files, through kactivitymanagerd's own call for forgetting them. */
    class Activities private constructor(private val connection: DBusConnection) : AutoCloseable {
        private val scoring = connection.getRemoteObject(BUS_NAME, PATH, ResourcesScoring::class.java)

        /** Forgets [path] and all under it, in every activity and for every app; it keeps local paths, not URLs. */
        fun forget(path: String) {
            runCatching {
                scoring.DeleteStatsForResource(ANY, ANY, path)
                scoring.DeleteStatsForResource(ANY, ANY, "$path/*")
            }.onFailure { log("the activity database refused (${it.javaClass.simpleName})") }
        }

        override fun close() = connection.close()

        /** org.kde.ActivityManager.ResourcesScoring, the call Coffer makes. Names are the wire format. */
        @DBusInterfaceName("org.kde.ActivityManager.ResourcesScoring")
        @Suppress("FunctionName")
        interface ResourcesScoring : DBusInterface {
            fun DeleteStatsForResource(activity: String, client: String, resource: String)
        }

        companion object {
            private const val BUS_NAME = "org.kde.ActivityManager"
            private const val PATH = "/ActivityManager/Resources/Scoring"
            private const val ANY = ":any"

            /** Null without a session bus or without KDE's activity manager. */
            fun open(): Activities? {
                val connection = runCatching { DBusConnectionBuilder.forSessionBus().build() }.getOrNull() ?: return null
                val running = runCatching {
                    connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).NameHasOwner(BUS_NAME)
                }.getOrDefault(false)
                if (!running) {
                    connection.close()
                    return null
                }
                return Activities(connection)
            }
        }
    }

    companion object {
        /** $XDG_DATA_HOME/recently-used.xbel, by default ~/.local/share/recently-used.xbel. */
        fun xbel(): Path {
            val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".local", "share")
            return base.resolve("recently-used.xbel")
        }
    }
}
