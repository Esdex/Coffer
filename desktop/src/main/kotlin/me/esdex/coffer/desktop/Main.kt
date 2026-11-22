package me.esdex.coffer.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.index.RoomVaultIndex
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.desktop.drive.DriveMount
import me.esdex.coffer.desktop.index.desktopVaultIndex
import me.esdex.coffer.desktop.keyring.Keyring
import me.esdex.coffer.desktop.keyring.KeyringException
import me.esdex.coffer.desktop.signin.SignIn
import me.esdex.coffer.desktop.tray.KdeTray
import me.esdex.coffer.desktop.tray.MenuEntry
import me.esdex.coffer.desktop.tray.TrayState
import me.esdex.coffer.desktop.tray.TrayView
import me.esdex.coffer.desktop.vault.DesktopVault
import me.esdex.coffer.desktop.vault.Unlock
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Coffer in the tray, signed in to Telegram; the vault, once unlocked, is a drive. Locking unmounts it, and
 * so does quitting. The mount point is under XDG_RUNTIME_DIR, which the system empties at logout, so nothing
 * is left in the home folder.
 */
fun main() {
    val keyring = try {
        Keyring.open()
    } catch (e: KeyringException) {
        fatal("Coffer cannot reach the system keyring", e)
    }
    val secrets = DesktopSecrets(keyring)
    // Before TDLib starts: a locked keyring shows its dialog now, and a refusal is said here instead of
    // leaving sign-in waiting for a key that never comes.
    val index = try {
        secrets.databaseKey()?.fill(0)
        openIndex(secrets)
    } catch (e: KeyringException) {
        fatal("Coffer cannot read its keys from the system keyring", e)
    }
    val sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))
    val telegram = desktopTelegram(secrets).also { it.start() }
    val vault = DesktopVault(sodium, telegram, secrets, index, dataDir()).also { it.start() }
    val signIn = AppWindow { close -> SignIn(telegram, onDone = close) }
    val unlock = AppWindow { close -> Unlock(vault, onDone = close) }

    val runtime = System.getenv("XDG_RUNTIME_DIR") ?: error("XDG_RUNTIME_DIR is not set")
    val drive = DriveMount(Path.of(runtime, "coffer"), vault)
    drive.follow()
    val quit = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { drive.unmount() })
    // The drive while unlocked; the unlock window before that.
    val open = { if (drive.isMounted) ProcessBuilder("xdg-open", drive.at.toString()).start() else unlock.show(); Unit }
    val tray = KdeTray(title = "Coffer", icon = listOf(22, 32, 64).map(::padlock), onActivate = open)
    showInTray(tray, telegram, vault, Actions(open = open, unlock = unlock::show, lock = vault::lock, signIn = signIn::show, quit = { quit.countDown() }))
    // First start, or signed out elsewhere: sign-in opens by itself; then unlocking, unless the vault
    // opened with a remembered key.
    thread(name = "start") {
        runBlocking {
            if (telegram.state.first { it != AuthState.Starting } != AuthState.Ready) signIn.show()
            telegram.state.first { it == AuthState.Ready }
            vault.settled.first { it }
            if (vault.manager.state.value !is VaultManager.State.Unlocked) unlock.show()
        }
    }
    quit.await()
    tray.close()
    drive.unmount()
    index.close()
    keyring.close()
    // The windows' AWT threads would keep the process alive.
    exitProcess(0)
}

/** The index, under its key from the keyring; a key the keyring lost means an index rebuilt from Saved Messages. */
private fun openIndex(secrets: DesktopSecrets): RoomVaultIndex {
    val file = File(dataDir(), "index.db")
    val key = secrets.indexKey() ?: run {
        for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete()
        secrets.resetIndexKey()
        checkNotNull(secrets.indexKey()) { "no key for the index" }
    }
    return desktopVaultIndex(file, key).also { key.fill(0) }
}

/** The tray's actions, made once: the menu is compared with what it showed to tell whether it changed. */
private class Actions(open: () -> Unit, unlock: () -> Unit, lock: () -> Unit, signIn: () -> Unit, quit: () -> Unit) {
    val open = MenuEntry.Action("Open Coffer drive", open)
    val unlock = MenuEntry.Action("Unlock", unlock)
    val lock = MenuEntry.Action("Lock", lock)
    val signIn = MenuEntry.Action("Sign in to Telegram", signIn)
    val quit = MenuEntry.Action("Quit", quit)
}

/**
 * Keeps the tray telling where Coffer stands: signed in or not, the vault locked or open, and what goes to
 * Telegram. At most once a second: a large upload reports progress far more often than a menu needs.
 */
private fun showInTray(tray: KdeTray, telegram: Telegram, vault: DesktopVault, actions: Actions) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        combine(telegram.state, vault.manager.state, vault.catchingUp, vault.transfers) { auth, state, catchingUp, transfers ->
            TrayState(
                signedIn = auth == AuthState.Ready,
                vault = when (state) {
                    VaultManager.State.Unknown, is VaultManager.State.Searching -> TrayState.Vault.LOOKING
                    VaultManager.State.NotFound -> TrayState.Vault.NONE
                    is VaultManager.State.Locked -> TrayState.Vault.LOCKED
                    is VaultManager.State.Unlocked -> TrayState.Vault.UNLOCKED
                },
                catchingUp = catchingUp,
                transfers = transfers,
            )
        }.conflate().collect { state ->
            val view = TrayView.of(state)
            val menu = buildList {
                view.lines.forEach { add(MenuEntry.Label(it)) }
                if (view.lines.isNotEmpty()) add(MenuEntry.Separator)
                add(actions.open)
                when (view.lock) {
                    TrayView.Lock.LOCK -> add(actions.lock)
                    TrayView.Lock.UNLOCK -> add(actions.unlock)
                    TrayView.Lock.NONE -> Unit
                }
                add(MenuEntry.Separator)
                if (!state.signedIn) add(actions.signIn)
                add(actions.quit)
            }
            tray.update(menu, view.status)
            delay(TRAY_PACE_MS)
        }
    }
}

private const val TRAY_PACE_MS = 1_000L

/** Says what stops Coffer from starting, in a window, and ends it when the window closes. */
private fun fatal(title: String, cause: Exception): Nothing {
    System.err.println("$title: ${cause.message}")
    application {
        Window(onCloseRequest = ::exitApplication, title = "Coffer") {
            MaterialTheme {
                Surface {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Text(cause.message.orEmpty())
                    }
                }
            }
        }
    }
    exitProcess(1)
}

/** A padlock, drawn, until the desktop has the app's own icon. */
private fun padlock(size: Int): BufferedImage {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val s = size.toDouble()
    image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        color = Color(0x3D7BD9)
        stroke = BasicStroke((s * 0.10).toFloat())
        draw(RoundRectangle2D.Double(s * 0.28, s * 0.10, s * 0.44, s * 0.50, s * 0.44, s * 0.44))
        fill(RoundRectangle2D.Double(s * 0.16, s * 0.42, s * 0.68, s * 0.50, s * 0.20, s * 0.20))
        color = Color.WHITE
        fill(Ellipse2D.Double(s * 0.43, s * 0.57, s * 0.14, s * 0.14))
        dispose()
    }
    return image
}
