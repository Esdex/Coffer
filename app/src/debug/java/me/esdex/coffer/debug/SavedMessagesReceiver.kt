package me.esdex.coffer.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StoredMessage
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.feature.browser.transfer.readyStorage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Debug builds only: lists Saved Messages to logcat, newest first, so checking what Coffer left there
 * needs nobody to scroll the chat. Ids, times, kinds and sizes; captions stay out (SPEC §8).
 *
 *     adb shell am broadcast -n <package>/me.esdex.coffer.debug.SavedMessagesReceiver [--ei limit 300]
 *
 * Only adb can send it: the receiver asks for DUMP (see the debug manifest).
 */
@AndroidEntryPoint
class SavedMessagesReceiver : BroadcastReceiver() {
    @Inject
    lateinit var telegram: Telegram

    override fun onReceive(context: Context, intent: Intent) {
        val limit = intent.getIntExtra(EXTRA_LIMIT, DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A background broadcast has a minute before Android calls it stuck.
                withTimeoutOrNull(TIMEOUT_MS) { list(limit) } ?: Log.w(TAG, "timed out")
            } catch (e: Exception) {
                Log.w(TAG, "failed: ${e::class.simpleName}")
            } finally {
                done.finish()
            }
        }
    }

    private suspend fun list(limit: Int) {
        val storage = telegram.readyStorage() ?: run {
            Log.w(TAG, "Telegram is not signed in")
            return
        }
        val pinned = storage.pinnedMessages().mapTo(HashSet()) { it.id }
        val messages = newest(storage, limit)
        Log.i(TAG, "begin: ${messages.size} newest messages")
        for (message in messages) Log.i(TAG, line(message, message.id in pinned))
        val kinds = messages.groupingBy { kind(it) }.eachCount().toSortedMap()
        val bytes = messages.sumOf { (it.content as? MessageContent.Blob)?.size ?: 0L }
        Log.i(TAG, "end: $kinds, blobs $bytes bytes")
    }

    private suspend fun newest(storage: StorageBackend, limit: Int): List<StoredMessage> {
        val all = ArrayList<StoredMessage>()
        var before: MessageId? = null
        while (all.size < limit) {
            val page = storage.historyBefore(before, minOf(StorageBackend.MAX_PAGE, limit - all.size))
            if (page.isEmpty()) break
            all += page
            before = page.last().id
        }
        return all
    }

    private fun line(message: StoredMessage, pinned: Boolean): String {
        val time = TIME.format(Instant.ofEpochSecond(message.date))
        val size = (message.content as? MessageContent.Blob)?.size?.let { " $it" }.orEmpty()
        return "${message.id.value} $time ${kind(message)}$size${if (pinned) " pinned" else ""}"
    }

    /**
     * Kind, format version and caption length, which anyone reading the chat sees anyway (SPEC §4.5 makes
     * the length one per kind in version 2); "foreign" for the rest.
     */
    private fun kind(message: StoredMessage): String {
        val text = when (val content = message.content) {
            is MessageContent.Text -> content.text
            is MessageContent.Blob -> content.caption
            MessageContent.Other -> return "foreign"
        }
        val record = Captions.parse(text) ?: return "foreign"
        return "${record.kind.tag} v${record.version} c${text.length}"
    }

    private companion object {
        const val TAG = "CofferSaved"
        const val EXTRA_LIMIT = "limit"
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 5_000
        const val TIMEOUT_MS = 50_000L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}
