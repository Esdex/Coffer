package me.esdex.coffer.feature.browser.transfer

/** What kind of transfer a queued one is, for the notification's wording. OPS: operations going to Telegram. */
enum class TransferKind { UPLOAD, DOWNLOAD, CACHE, OPS }

/**
 * The one notification of the whole queue: what it is doing, [finished] of [total] transfers of this run,
 * and how far along it all is. Running transfers count by how much of each has moved; one still
 * preparing, or with nothing known yet, leaves the bar running rather than standing still.
 */
data class QueueSummary(
    /** All the same kind, or null for a mix. */
    val kind: TransferKind?,
    val finished: Int,
    val total: Int,
    /** 0..100 of the whole run, or null while nothing measurable is moving. */
    val percent: Int?,
    /** Bytes moved and to move of the transfers running now, for "12 MB of 40 MB". */
    val done: Long,
    val size: Long,
) {
    companion object {
        /** [running] maps each running transfer's kind to its progress, null until it has any. */
        fun of(kinds: Collection<TransferKind>, finished: Int, total: Int, running: List<Pair<TransferKind, TransferRegistry.Progress?>>): QueueSummary {
            val kind = kinds.distinct().singleOrNull()
            val moving = running.mapNotNull { it.second }.filter { it.phase == TransferRegistry.Phase.MOVING && it.total > 0 }
            val share = moving.sumOf { it.done.toDouble() / it.total }
            val percent = if (total > 0 && (moving.isNotEmpty() || finished > 0)) {
                ((finished + share) * 100 / total).toInt().coerceIn(0, 100)
            } else {
                null
            }
            return QueueSummary(kind, finished, total, percent, moving.sumOf { it.done }, moving.sumOf { it.total })
        }
    }
}
