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
import me.esdex.coffer.desktop.drive.PreviewSweeper
import me.esdex.coffer.desktop.drive.RecentFiles
import me.esdex.coffer.desktop.index.desktopVaultIndex
import me.esdex.coffer.desktop.keyring.Keyring
import me.esdex.coffer.desktop.keyring.KeyringException
import me.esdex.coffer.desktop.network.NetworkWatch
import me.esdex.coffer.desktop.signin.LogOut
import me.esdex.coffer.desktop.signin.SignIn
import me.esdex.coffer.desktop.tray.KdeTray
import me.esdex.coffer.desktop.tray.MenuEntry
import me.esdex.coffer.desktop.tray.TrayIcons
import me.esdex.coffer.desktop.tray.TrayState
import me.esdex.coffer.desktop.tray.TrayView
import me.esdex.coffer.desktop.tray.asLink
import me.esdex.coffer.desktop.vault.DesktopVault
import me.esdex.coffer.desktop.vault.Unlock
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
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
        fatal("${BuildInfo.variant.title} cannot reach the system keyring", e)
    }
    val secrets = DesktopSecrets(keyring)
    // Before TDLib starts: a locked keyring shows its dialog now, and a refusal is said here instead of
    // leaving sign-in waiting for a key that never comes.
    val index = try {
        secrets.databaseKey()?.fill(0)
        openIndex(secrets)
    } catch (e: KeyringException) {
        fatal("${BuildInfo.variant.title} cannot read its keys from the system keyring", e)
    }
    val sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))
    val telegram = desktopTelegram(secrets).also { it.start() }
    val network = NetworkWatch.start { up -> if (up) telegram.networkChanged() else telegram.networkLost() }
    val vault = DesktopVault(sodium, telegram, secrets, index, dataDir()).also { it.start() }
    val signIn = AppWindow { close -> SignIn(telegram, onDone = close) }
    val unlock = AppWindow { close -> Unlock(vault, onDone = close) }
    val logOut = AppWindow { close -> LogOut(telegram, vault, onDone = close) }

    val settings = DesktopSettings(File(dataDir(), "settings.properties"))
    val runtime = System.getenv("XDG_RUNTIME_DIR") ?: error("XDG_RUNTIME_DIR is not set")
    val at = Path.of(runtime, BuildInfo.variant.id)
    // Left by a crash or a kill, then each as it comes.
    val previews = PreviewSweeper(PreviewSweeper.cache(), at).apply {
        runCatching { sweep() }
        watch()
    }
    val recent = RecentFiles(RecentFiles.xbel(), at, RecentFiles.Activities.open()).apply {
        runCatching { sweep() }
        watch()
    }
    val drive = DriveMount(at, vault, settings.showInDolphin, sweep = {
        previews.sweep()
        recent.sweep()
    })
    drive.follow()
    val quit = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { drive.unmount() })
    // The drive while unlocked; the unlock window before that.
    val open = { if (drive.isMounted) ProcessBuilder("xdg-open", drive.at.toString()).start() else unlock.show(); Unit }
    val themePath = TrayIcons.install(File(dataDir(), "icons"))
    val click = AtomicReference(TrayView.Click.SIGN_IN)
    // A host that shows no menu on a left click, as Plasma does for an item that asks for it, opens the drive.
    val activate = {
        when (click.get()) {
            TrayView.Click.SIGN_IN -> signIn.show()
            TrayView.Click.UNLOCK -> unlock.show()
            TrayView.Click.MENU -> open()
        }
    }
    val tray = KdeTray(id = BuildInfo.variant.id, title = BuildInfo.variant.title, icon = TrayIcons.of(TrayIcons.Look.LOCKED), onActivate = activate)
    val actions = Actions(
        open = open, unlock = unlock::show, lock = vault::lock, signIn = signIn::show, logOut = logOut::show, quit = { quit.countDown() },
        monochrome = { settings.setMonochromeIcon(!settings.monochromeIcon.value) },
        dolphin = { settings.setShowInDolphin(!settings.showInDolphin.value) },
    )
    showInTray(tray, telegram, vault, settings, themePath, actions, click)
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
    network?.close()
    drive.unmount()
    previews.close()
    recent.close()
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
private class Actions(
    open: () -> Unit,
    unlock: () -> Unit,
    lock: () -> Unit,
    signIn: () -> Unit,
    logOut: () -> Unit,
    quit: () -> Unit,
    val monochrome: () -> Unit,
    val dolphin: () -> Unit,
) {
    val open = MenuEntry.Action("Open Coffer drive", open)
    val unlock = MenuEntry.Action("Unlock", unlock)
    val lock = MenuEntry.Action("Lock", lock)
    val signIn = MenuEntry.Action("Sign in to Telegram", signIn)
    val logOut = MenuEntry.Action("Log out…", logOut)
    val quit = MenuEntry.Action("Quit", quit)
}

/**
 * Keeps the tray telling where Coffer stands: signed in or not, Telegram in reach or not, the vault locked or
 * open, and what goes to Telegram. At most once a second: a large upload reports progress far more often than a menu needs.
 */
private fun showInTray(
    tray: KdeTray,
    telegram: Telegram,
    vault: DesktopVault,
    settings: DesktopSettings,
    themePath: String,
    actions: Actions,
    click: AtomicReference<TrayView.Click>,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        val telegramState = combine(telegram.state, telegram.connection.asLink(), ::Pair)
        val looks = combine(settings.monochromeIcon, settings.showInDolphin, ::Pair)
        combine(telegramState, vault.manager.state, vault.catchingUp, vault.transfers, looks) { (auth, link), state, catchingUp, transfers, looks ->
            looks to TrayState(
                signedIn = auth == AuthState.Ready,
                vault = when (state) {
                    VaultManager.State.Unknown, is VaultManager.State.Searching -> TrayState.Vault.LOOKING
                    VaultManager.State.NotFound -> TrayState.Vault.NONE
                    is VaultManager.State.Locked -> TrayState.Vault.LOCKED
                    is VaultManager.State.Unlocked -> TrayState.Vault.UNLOCKED
                },
                catchingUp = catchingUp,
                transfers = transfers,
                link = link,
            )
        }.conflate().collect { (looks, state) ->
            val (monochrome, dolphin) = looks
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
                add(
                    MenuEntry.Submenu(
                        "Settings",
                        listOf(
                            MenuEntry.Toggle("Show the drive in Dolphin", dolphin, actions.dolphin),
                            MenuEntry.Toggle("Monochrome icon", monochrome, actions.monochrome),
                        ),
                    ),
                )
                if (state.signedIn) add(actions.logOut)
                add(actions.quit)
            }
            click.set(view.click)
            tray.clickOpensMenu(view.click == TrayView.Click.MENU)
            tray.update(menu, view.status)
            tray.icon(TrayIcons.tray(view.icon, monochrome, themePath))
            delay(TRAY_PACE_MS)
        }
    }
}

private const val TRAY_PACE_MS = 1_000L

/** Says what stops Coffer from starting, in a window, and ends it when the window closes. */
private fun fatal(title: String, cause: Exception): Nothing {
    System.err.println("$title: ${cause.message}")
    application {
        Window(onCloseRequest = ::exitApplication, title = BuildInfo.variant.title) {
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
