package me.esdex.coffer.desktop.tray

import me.esdex.coffer.core.sync.TransferStage
import me.esdex.coffer.desktop.vault.VaultWriter

/** Where Coffer stands, as the tray tells it. */
data class TrayState(
    val signedIn: Boolean,
    val vault: Vault,
    /** Messages read so far while catching up, or null. */
    val catchingUp: Int?,
    val transfers: VaultWriter.Transfers,
) {
    enum class Vault { LOOKING, NONE, LOCKED, UNLOCKED }
}

/**
 * What the tray shows: a line for its tooltip, the lines at the top of its menu, and whether its menu offers to
 * lock or to unlock. Names of files show here as on the phone's transfer notifications; they never go to a log.
 */
data class TrayView(val status: String, val lines: List<String>, val lock: Lock) {
    enum class Lock { LOCK, UNLOCK, NONE }

    companion object {
        fun of(state: TrayState): TrayView {
            if (!state.signedIn) return TrayView("Not signed in to Telegram", emptyList(), Lock.NONE)
            return when (state.vault) {
                TrayState.Vault.LOOKING -> TrayView("Looking for your vault", emptyList(), Lock.NONE)
                TrayState.Vault.NONE -> TrayView("No vault in this account yet", emptyList(), Lock.NONE)
                TrayState.Vault.LOCKED -> TrayView("Locked", emptyList(), Lock.UNLOCK)
                TrayState.Vault.UNLOCKED -> unlocked(state.transfers, state.catchingUp)
            }
        }

        private fun unlocked(transfers: VaultWriter.Transfers, catchingUp: Int?): TrayView {
            val lines = mutableListOf<String>()
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
                transfers.uploading.size == 1 && transfers.waiting == 0 -> lines.first()
                transfers.uploading.isNotEmpty() || transfers.waiting > 0 -> {
                    val files = transfers.uploading.size + transfers.waiting
                    "Uploading $files files"
                }
                transfers.operations > 0 -> lines.first()
                catchingUp != null -> "Reading what changed"
                else -> "Up to date"
            }
            if (lines.isEmpty()) lines += "Up to date"
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

        private const val KB = 1024L
        private const val MB = KB * 1024
        private const val GB = MB * 1024
        private const val MAX_NAME = 40
        private const val TAIL = 10
    }
}
