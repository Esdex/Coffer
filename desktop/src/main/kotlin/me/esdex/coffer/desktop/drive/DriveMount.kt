package me.esdex.coffer.desktop.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.desktop.BuildInfo
import me.esdex.coffer.desktop.vault.DesktopVault
import me.esdex.coffer.desktop.vault.log
import java.nio.file.Path

/**
 * The drive at [at] for as long as [vault] is unlocked (Esdex, 2026-11-21: lock = unmount), shown in
 * Dolphin's Places while it is there if [showInPlaces], which takes effect at once. When it goes, [sweep] takes
 * what the desktop kept of its files out of its caches and lists ([PreviewSweeper], [RecentFiles]).
 */
class DriveMount(
    val at: Path,
    private val vault: DesktopVault,
    private val showInPlaces: StateFlow<Boolean>,
    private val sweep: () -> Unit = {},
    private val places: PlacesBookmark = PlacesBookmark.kde("${BuildInfo.variant.id}-drive"),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mounted: VaultDrive.Mounted? = null

    val isMounted: Boolean @Synchronized get() = mounted != null

    fun follow() {
        // Left by a crash: the drive is not there yet.
        quietly("taking the drive out of Places") { places.remove() }
        scope.launch {
            vault.keys.map { it != null }.distinctUntilChanged().collect { unlocked -> if (unlocked) mount() else unmount() }
        }
        scope.launch { showInPlaces.drop(1).collect { show -> place(show) } }
    }

    @Synchronized
    private fun place(show: Boolean) {
        if (mounted == null) return
        if (show) quietly("putting the drive in Places") { places.add(at, BuildInfo.variant.title) }
        else quietly("taking the drive out of Places") { places.remove() }
    }

    @Synchronized
    private fun mount() {
        if (mounted != null) return
        mounted = try {
            VaultDrive.mount(at, tree = { vault.tree.value }, open = { runBlocking { vault.openFile(it) } }, edits = { vault.edits }, space = vault.draftsDir.toPath().parent).also {
                log("drive mounted")
                if (showInPlaces.value) quietly("putting the drive in Places") { places.add(at, BuildInfo.variant.title) }
            }
        } catch (e: Exception) {
            log("mounting the drive failed (${e.javaClass.simpleName})")
            null
        }
    }

    @Synchronized
    fun unmount() {
        val drive = mounted ?: return
        mounted = null
        // Out of Places first: a place that leads nowhere is worse than none.
        quietly("taking the drive out of Places") { places.remove() }
        runCatching { drive.close() }.onFailure { log("unmounting the drive failed (${it.javaClass.simpleName})") }
        log("drive unmounted")
        quietly("sweeping what the drive left") { sweep() }
    }

    /** Places is a convenience: the drive works without it, so a failure there is only logged. */
    private inline fun quietly(what: String, block: () -> Unit) {
        runCatching(block).onFailure { log("$what failed (${it.javaClass.simpleName})") }
    }
}
