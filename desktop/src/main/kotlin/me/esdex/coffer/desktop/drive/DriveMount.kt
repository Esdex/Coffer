package me.esdex.coffer.desktop.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.desktop.vault.DesktopVault
import me.esdex.coffer.desktop.vault.log
import java.nio.file.Path

/** The drive at [at] for as long as [vault] is unlocked (Esdex, 2026-11-21: lock = unmount). */
class DriveMount(val at: Path, private val vault: DesktopVault) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mounted: VaultDrive.Mounted? = null

    val isMounted: Boolean @Synchronized get() = mounted != null

    fun follow() {
        scope.launch {
            vault.keys.map { it != null }.distinctUntilChanged().collect { unlocked -> if (unlocked) mount() else unmount() }
        }
    }

    @Synchronized
    private fun mount() {
        if (mounted != null) return
        mounted = try {
            VaultDrive.mount(at, tree = { vault.tree.value }, open = { runBlocking { vault.openFile(it) } }, edits = { vault.edits }, space = vault.draftsDir.toPath().parent).also { log("drive mounted") }
        } catch (e: Exception) {
            log("mounting the drive failed (${e.javaClass.simpleName})")
            null
        }
    }

    @Synchronized
    fun unmount() {
        val drive = mounted ?: return
        mounted = null
        runCatching { drive.close() }.onFailure { log("unmounting the drive failed (${it.javaClass.simpleName})") }
        log("drive unmounted")
    }
}
