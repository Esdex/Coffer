package me.esdex.coffer.desktop.tray

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import me.esdex.coffer.core.sync.TransferStage
import me.esdex.coffer.core.tdlib.Connection
import me.esdex.coffer.desktop.vault.VaultWriter

/** Where Coffer stands, as the tray tells it. */
data class TrayState(
    val signedIn: Boolean,
    val vault: Vault,
    /** Messages read so far while catching up, or null. */
    val catchingUp: Int?,
    val transfers: VaultWriter.Transfers,
    val link: Link = Link.UP,
) {
    enum class Vault { LOOKING, NONE, LOCKED, UNLOCKED }

    /** Coffer's link to Telegram, as worth telling: connecting only once it lasts ([asLink]). */
    enum class Link { UP, WAITING_FOR_NETWORK, CONNECTING }
}

/**
 * [Connection] as the tray tells it (Esdex, 2026-11-23): no network at once, as TDLib finds it out itself; connecting
 * only after [patienceMs], since TDLib connects for a moment at every start and after every change of network.
 * Updating is catching up, which the tray tells of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<Connection>.asLink(patienceMs: Long = CONNECTING_PATIENCE_MS): Flow<TrayState.Link> = transformLatest { connection ->
    when (connection) {
        Connection.WAITING_FOR_NETWORK -> emit(TrayState.Link.WAITING_FOR_NETWORK)
        Connection.CONNECTING -> {
            emit(TrayState.Link.UP)
            delay(patienceMs)
            emit(TrayState.Link.CONNECTING)
        }
        Connection.UPDATING, Connection.READY -> emit(TrayState.Link.UP)
    }
}.distinctUntilChanged()

private const val CONNECTING_PATIENCE_MS = 10_000L

/**
 * What the tray shows: a line for its tooltip, the lines at the top of its menu, whether its menu offers to
 * lock or to unlock, whether Telegram is out of reach, and what a left click does. Names of files show here as on
 * the phone's transfer notifications; they never go to a log.
 */
data class TrayView(val status: String, val lines: List<String>, val lock: Lock, val offline: Boolean = false, val click: Click = Click.MENU) {
    enum class Lock { LOCK, UNLOCK, NONE }

    /**
     * A left click opens what stands in the way, sign-in or unlocking, and otherwise the menu, as a right click
     * always does (Esdex, 2026-11-23).
     */
    enum class Click { MENU, SIGN_IN, UNLOCK }

    /**
     * Grey unless the vault is open; marked while Telegram is out of reach, as nothing moves then; busy while
     * anything goes to or comes from Telegram.
     */
    val icon: TrayIcons.Look
        get() = when {
            lock != Lock.LOCK -> TrayIcons.Look.LOCKED
            offline -> TrayIcons.Look.OFFLINE
            lines != listOf(UP_TO_DATE) -> TrayIcons.Look.BUSY
            else -> TrayIcons.Look.OPEN
        }

    companion object {
        fun of(state: TrayState): TrayView {
            if (!state.signedIn) return TrayView("Not signed in to Telegram", emptyList(), Lock.NONE, click = Click.SIGN_IN)
            val link = when (state.link) {
                TrayState.Link.UP -> null
                TrayState.Link.WAITING_FOR_NETWORK -> "Waiting for network"
                TrayState.Link.CONNECTING -> "Connecting to Telegram"
            }
            val view = when (state.vault) {
                // Looking goes no further without Telegram, so the tooltip tells why.
                TrayState.Vault.LOOKING -> TrayView(link ?: "Looking for your vault", emptyList(), Lock.NONE)
                TrayState.Vault.NONE -> TrayView("No vault in this account yet", listOfNotNull(link), Lock.NONE)
                TrayState.Vault.LOCKED -> TrayView("Locked", listOfNotNull(link), Lock.UNLOCK, click = Click.UNLOCK)
                TrayState.Vault.UNLOCKED -> unlocked(state.transfers, state.catchingUp, link)
            }
            return view.copy(offline = link != null)
        }

        private fun unlocked(transfers: VaultWriter.Transfers, catchingUp: Int?, link: String?): TrayView {
            // First: the uploads and changes below wait for it.
            val lines = listOfNotNull(link).toMutableList()
            for (upload in transfers.uploading) {
                lines += when {
                    upload.stage == TransferStage.PREPARING && upload.done == 0L -> "Preparing ${short(upload.name)}"
                    else -> "Uploading ${short(upload.name)}: ${percent(upload.done, upload.total)}% (${sizes(upload.done, upload.total)})"
                }
            }
            if (transfers.waiting > 0) {
                val more = if (transfers.uploading.isEmpty()) "" else " more"
                lines += if (transfers.waiting == 1) "1$more file waiting" else "${transfers.waiting}$more files waiting"
            }
            if (transfers.operations > 0) {
                lines += if (transfers.operations == 1) "Sending 1 change" else "Sending ${transfers.operations} changes"
            }
            if (catchingUp != null) lines += "Reading what changed: $catchingUp messages"
            val status = when {
                link != null -> link
                transfers.uploading.size == 1 && transfers.waiting == 0 -> lines.first()
                transfers.uploading.isNotEmpty() || transfers.waiting > 0 -> {
                    val files = transfers.uploading.size + transfers.waiting
                    "Uploading $files files"
                }
                transfers.operations > 0 -> lines.first()
                catchingUp != null -> "Reading what changed"
                else -> UP_TO_DATE
            }
            if (lines.isEmpty()) lines += UP_TO_DATE
            return TrayView(status, lines, Lock.LOCK)
        }

        private fun percent(done: Long, total: Long): Long = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)

        /** "82 of 182 MB", both in the unit of the whole. */
        internal fun sizes(done: Long, total: Long): String {
            val (unit, name) = when {
                total >= GB -> GB to "GB"
                total >= MB -> MB to "MB"
                total >= KB -> KB to "KB"
                else -> 1L to "bytes"
            }
            fun of(bytes: Long) = if (unit >= GB) "%.1f".format(bytes.toDouble() / unit) else (bytes / unit).toString()
            return "${of(done)} of ${of(total)} $name"
        }

        /** A name a menu line can hold; the end kept, where the extension is. */
        internal fun short(name: String): String =
            if (name.length <= MAX_NAME) name else name.take(MAX_NAME - TAIL - 1) + "…" + name.takeLast(TAIL)

        private const val UP_TO_DATE = "Up to date"
        private const val KB = 1024L
        private const val MB = KB * 1024
        private const val GB = MB * 1024
        private const val MAX_NAME = 40
        private const val TAIL = 10
    }
}
