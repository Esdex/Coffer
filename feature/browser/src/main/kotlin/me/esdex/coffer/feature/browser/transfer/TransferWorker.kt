package me.esdex.coffer.feature.browser.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import me.esdex.coffer.core.crypto.AuthenticationException
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.ContentMismatchException
import me.esdex.coffer.core.sync.DownloadRunner
import me.esdex.coffer.core.sync.MissingPartException
import me.esdex.coffer.core.sync.NotEnoughSpaceException
import me.esdex.coffer.core.sync.OpQueue
import me.esdex.coffer.core.sync.PrefetchRunner
import me.esdex.coffer.core.sync.SourceChangedException
import me.esdex.coffer.core.sync.TransferStage
import me.esdex.coffer.core.sync.UploadRunner
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.feature.browser.R

/**
 * The transfer queue: every upload, download and file being cached, in one foreground job with one
 * notification for all of them (SPEC §6). The first version ran each transfer as a job of its own, and a
 * folder of files made notifications come and go one after another (Esdex, 2026-11-12).
 *
 * Up to three run at once ([TransferRegistry.gate]); new work wakes the run that is going (see
 * [QueueState]). A transfer holds only its file's DEK, from [TransferRegistry]; one without it (the process
 * restarted and the vault is locked) waits for the unlock that provides it, and the queue says it is
 * paused. A file being cached needs no key at all. The notification never names a file: it can show on
 * the lock screen.
 */
@HiltWorker
class TransferWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sodium: Sodium,
    private val telegram: Telegram,
    private val index: VaultIndex,
    private val registry: TransferRegistry,
    private val cache: FileCache,
    private val vault: VaultManager,
    private val inbox: ShareInbox,
) : CoroutineWorker(context, params) {

    /** A transfer waiting in the queue; [ready] when it has what it needs to run now. */
    private class Item(val id: String, val kind: TransferKind, val ready: Boolean)

    override suspend fun doWork(): Result {
        // Requests left by an older version of Coffer, one per transfer, would each run a queue: one is enough.
        if (!running.tryLock()) return Result.success()
        var seen = registry.queue.started()
        var paused = false
        try {
            coroutineScope {
                // The loop's own: the transfers running. The rest is read by the transfers and the
                // notification too, on other threads.
                val active = HashMap<String, Pair<TransferKind, Job>>()
                val kinds = ConcurrentHashMap<String, TransferKind>()
                val finished = AtomicInteger()
                val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()
                var notice: Job? = null
                while (isActive) {
                    val now = registry.queue.seen()
                    if (now != seen) {
                        // Something changed: what waited for a key may have it now.
                        waiting.clear()
                        seen = now
                    }
                    val items = pending()
                    val startable = items.filter { it.ready && it.id !in active && it.id !in waiting }
                    if (startable.isNotEmpty() && notice == null) {
                        if (!goForeground()) {
                            paused = true
                            break
                        }
                        notice = launch { keepNoticeUp(kinds, finished) }
                    }
                    for (item in startable) {
                        kinds[item.id] = item.kind
                        registry.queued(item.id)
                        val job = launch {
                            try {
                                if (!runItem(item)) waiting += item.id
                            } finally {
                                endProgress(item.id)
                                registry.finished(item.id)
                                registry.wake.trySend(Unit)
                            }
                        }
                        registry.track(item.id, job)
                        active[item.id] = item.kind to job
                    }
                    active.entries.removeAll { (id, entry) ->
                        val done = entry.second.isCompleted
                        if (done) {
                            registry.untrack(id, entry.second)
                            if (id !in waiting) finished.incrementAndGet()
                        }
                        done
                    }
                    runningNow = active.map { (id, entry) -> id to entry.first }
                    registry.run(TransferRegistry.Run(finished.get(), kinds.size))
                    if (active.isEmpty()) {
                        // Left now: only what cannot run yet. Over unless something came in since it looked.
                        paused = items.any { !it.ready || it.id in waiting }
                        if (registry.queue.finishing(seen)) break
                        continue
                    }
                    registry.wake.receive()
                }
                notice?.cancel()
            }
        } catch (e: CancellationException) {
            // Stopped by WorkManager (no network, the system): it starts the queue again later.
            registry.queue.stopped()
            throw e
        } finally {
            registry.run(null)
            running.unlock()
        }
        if (paused) showPaused()
        return Result.success()
    }

    /** Uploads with their file prepared, downloads, files to cache; not those failed, which wait for Retry. */
    private suspend fun pending(): List<Item> {
        val failed = registry.failures.value
        // Operations first, as one transfer that sends them all: an upload into a folder made a moment ago
        // waits until that folder is in Saved Messages. Named after the first one when a run starts, and
        // keeping that name while it runs: named after the first one each time, a run looked new after
        // every operation sent, and a second one started beside it.
        val queuedOps = index.ops()
        val ops = queuedOps.firstOrNull()?.let { first ->
            listOf(Item(opsRun ?: opsId(first.seq).also { opsRun = it }, TransferKind.OPS, ready = true))
        }.orEmpty()
        val uploads = index.uploads().filter { it.uploadId !in failed }.map { upload ->
            val entry = upload.fileId?.let(registry::get)
            Item(upload.uploadId, TransferKind.UPLOAD, entry?.source != null && queuedOps.isEmpty())
        }
        val downloads = index.downloads().filter { it.downloadId !in failed }.map { download ->
            val entry = registry.get(download.fileId)
            // An export whose target is not in memory yet waits for the unlock that provides it.
            Item(download.downloadId, TransferKind.DOWNLOAD, entry != null && (download.sealedTarget == null || entry.target != null))
        }
        val caching = cache.queue.items.value.filter { it.id !in failed }.map { Item(it.id, TransferKind.CACHE, ready = true) }
        return ops + uploads + downloads + caching
    }

    /**
     * One transfer, through to its end: done, failed for good, or set aside (false) because what it needs
     * is gone. Telegram out of reach is waited out and tried again, longer each time; FLOOD_WAIT as long as
     * Telegram asks (SPEC §6).
     */
    private suspend fun runItem(item: Item): Boolean {
        var backoff = FIRST_RETRY_MS
        while (true) {
            try {
                return registry.gate.withPermit {
                    when (item.kind) {
                        TransferKind.OPS -> sendOps(item.id)
                        TransferKind.UPLOAD -> upload(item.id)
                        TransferKind.DOWNLOAD -> download(item.id)
                        TransferKind.CACHE -> prefetch(item.id)
                    }
                }
            } catch (e: FloodWaitException) {
                waitOut(item.id, TransferRegistry.Wait.FLOOD, e.seconds * 1000L)
            } catch (_: StorageException) {
                waitOut(item.id, TransferRegistry.Wait.RETRY, backoff)
                backoff = (backoff * 2).coerceAtMost(LAST_RETRY_MS)
            }
        }
    }

    /** Waits, and says on the Transfers screen what for and how long. */
    private suspend fun waitOut(id: String, reason: TransferRegistry.Wait, millis: Long) {
        registry.waiting(id, TransferRegistry.Waiting(reason, System.currentTimeMillis() + millis))
        try {
            delay(millis)
        } finally {
            registry.waited(id)
        }
    }

    private suspend fun upload(id: String): Boolean {
        val upload = index.uploads().find { it.uploadId == id } ?: return true
        val fileId = upload.fileId ?: return false
        val entry = registry.get(fileId) ?: return false
        val source = entry.source ?: return true
        val storage = telegram.readyStorage() ?: throw StorageException("Telegram is not ready")
        val runner = UploadRunner(sodium, storage, index, WorkFiles.upload(applicationContext))
        try {
            runner.run(upload, entry.keys, inbox.source(source)) { done, total, stage -> report(id, done, total, stage) }
            registry.remove(fileId)
            // A file shared into Coffer was copied for this upload (SPEC §6, step 1); done with it.
            ShareInbox.deleteCopy(applicationContext, source)
            releaseFolderGrants(applicationContext, index)
        } catch (_: SourceChangedException) {
            // Abandoned and out of the queue: its copy, if any, has nothing left to serve.
            ShareInbox.deleteCopy(applicationContext, source)
            registry.remove(fileId)
            registry.fail(id, TransferRegistry.Failure.SOURCE_CHANGED)
        } catch (_: NotEnoughSpaceException) {
            registry.fail(id, TransferRegistry.Failure.NO_SPACE)
        } catch (_: FileNotFoundException) {
            registry.fail(id, TransferRegistry.Failure.SOURCE_GONE)
        } catch (_: SecurityException) {
            registry.fail(id, TransferRegistry.Failure.SOURCE_GONE)
        }
        return true
    }

    /**
     * Sends the queued operations at Telegram's pace (see [OpQueue]), with how many of them have gone as
     * this transfer's progress. A FLOOD_WAIT comes out of here and is waited out, and shown, by [runItem].
     */
    private suspend fun sendOps(id: String): Boolean = try {
        sendQueuedOps(id)
    } finally {
        // The next run is a new transfer, with a name of its own.
        if (opsRun == id) opsRun = null
    }

    /** The run of the operation queue going now, if one is. */
    @Volatile
    private var opsRun: String? = null

    private suspend fun sendQueuedOps(id: String): Boolean {
        val storage = telegram.readyStorage() ?: throw StorageException("Telegram is not ready")
        var total = index.ops().size
        report(id, 0, total.toLong(), TransferStage.MOVING)
        OpQueue(index).drain(storage, pause = { delay(OpQueue.PACE_MILLIS) }) { left ->
            // Some may have been added meanwhile.
            val sent = total - left.size
            total = maxOf(total, sent + left.size)
            report(id, sent.toLong(), total.toLong(), TransferStage.MOVING)
        }
        return true
    }

    private suspend fun download(id: String): Boolean {
        val download = index.downloads().find { it.downloadId == id } ?: return true
        val entry = registry.get(download.fileId) ?: return false
        val target = entry.target?.let(Uri::parse)
        if (download.sealedTarget != null && target == null) return false
        val open = entry.open
        if (open != null && vault.state.value !is VaultManager.State.Unlocked) {
            // Queued before the vault locked: its copy would be for nobody, and written decrypted onto a
            // locked phone (audit 2, L-m).
            index.removeDownload(id)
            registry.remove(download.fileId)
            return true
        }
        val storage = telegram.readyStorage() ?: throw StorageException("Telegram is not ready")
        val openFile = open?.let { File(File(OpenFiles.dir(applicationContext), id), OpenFiles.safeName(it.name)) }
        // Whatever ends this download but a hand-over to the other app, cancelling included, takes the copy.
        var handedOver = false
        val failure = try {
            val outcome = try {
                val output = if (target != null) {
                    applicationContext.contentResolver.openOutputStream(target, "wt") ?: throw FileNotFoundException()
                } else {
                    // Before the first byte: a process killed halfway still has its copy swept.
                    OpenFilesSweeper.schedule(applicationContext)
                    checkNotNull(openFile).also { it.parentFile?.mkdirs() }.outputStream()
                }
                output.use {
                    DownloadRunner(sodium, storage, index) { part -> cache.keep(download.fileId, part, storage) }
                        .run(download.fileId, entry.keys, it) { done, total -> report(id, done, total, TransferStage.MOVING) }
                }
                null
            } catch (_: ContentMismatchException) {
                TransferRegistry.Failure.CONTENT_MISMATCH
            } catch (_: MissingPartException) {
                TransferRegistry.Failure.MISSING_PART
            } catch (_: FileNotFoundException) {
                TransferRegistry.Failure.TARGET_FAILED
            } catch (_: SecurityException) {
                // The place picked to save into is no longer ours to write, as after a restart without a lasting
                // grant. Out of the worker, it stopped every transfer, again at each start, and WorkManager logged
                // its message, which names the document (audit 2, L-n).
                TransferRegistry.Failure.TARGET_FAILED
            } catch (_: IllegalArgumentException) {
                TransferRegistry.Failure.TARGET_FAILED
            } catch (_: AuthenticationException) {
                TransferRegistry.Failure.CONTENT_MISMATCH
            }
            if (outcome != null) {
                // A file that did not verify must not be left looking like the real thing.
                if (target != null) runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, target) }
                registry.fail(id, outcome)
            } else if (openFile != null && open != null) {
                // Finished after the vault locked, which cleared the copies: this one is not to stay either (audit 1, M11).
                if (vault.state.value is VaultManager.State.Unlocked) {
                    registry.readyToOpen(openFile, open.mime)
                    OpenFilesSweeper.schedule(applicationContext)
                    handedOver = true
                }
            }
            outcome
        } finally {
            if (!handedOver) openFile?.parentFile?.deleteRecursively()
        }
        index.removeDownload(id)
        if (failure == null) registry.remove(download.fileId)
        releaseFolderGrants(applicationContext, index)
        return true
    }

    /**
     * Every part of a file onto the phone whole, into the cache. Done, or the file gone, it leaves the
     * queue; a failure stays there with Retry. With the cache turned off since, there is nowhere to put it.
     */
    private suspend fun prefetch(id: String): Boolean {
        val item = cache.queue.find(id) ?: return true
        val file = index.file(item.fileId)?.takeIf { it.status == FileStatus.READY }
        if (file == null || !cache.prefs.value.enabled) {
            cache.queue.remove(id)
            return true
        }
        val storage = telegram.readyStorage() ?: throw StorageException("Telegram is not ready")
        try {
            PrefetchRunner(storage, index) { part -> cache.keep(item.fileId, part, storage) }
                .run(item.fileId) { done, total -> report(id, done, total, TransferStage.MOVING) }
        } catch (_: MissingPartException) {
            registry.fail(id, TransferRegistry.Failure.MISSING_PART)
            return true
        }
        cache.queue.remove(id)
        return true
    }

    // Transfers that have ended: TDLib reports progress from its own thread, and can do so after the
    // transfer has returned; such a report must not put its progress back (seen 2026-11-11).
    private val ended = HashSet<String>()

    private fun endProgress(id: String) = synchronized(ended) { ended += id }

    private fun report(id: String, done: Long, total: Long, stage: TransferStage) {
        synchronized(ended) {
            if (id in ended) return
            val phase = if (stage == TransferStage.PREPARING) TransferRegistry.Phase.PREPARING else TransferRegistry.Phase.MOVING
            registry.progress(id, done, total, phase)
        }
    }

    /**
     * The one notification, brought up to date once a second from the queue's own record, not from each
     * transfer's progress: Android drops updates beyond about five a second.
     */
    // What runs now, as the loop last left it, for the notification on its own thread.
    @Volatile
    private var runningNow: List<Pair<String, TransferKind>> = emptyList()

    private suspend fun keepNoticeUp(kinds: Map<String, TransferKind>, finished: AtomicInteger) {
        while (true) {
            val progress = registry.progress.value
            val running = runningNow.map { (id, kind) -> kind to progress[id] }
            val summary = QueueSummary.of(kinds.values.toList(), finished.get(), kinds.size, running)
            // Straight to the notification: setForegroundAsync also restarts the service, which posts it
            // twice milliseconds apart, and Android then drops one now and then (seen 2026-11-09).
            if (!isStopped) runCatching { notifications().notify(NOTICE_ID, notification(summary).setOngoing(true).build()) }
            delay(NOTICE_EVERY_MS)
        }
    }

    /**
     * Ends with transfers left that cannot run: the vault is locked after a restart, or Android refused the
     * foreground service. They stay queued; this says so, instead of leaving the user to guess.
     */
    private fun showPaused() {
        val notification = builder()
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(applicationContext.getString(R.string.transfers_paused))
            .setContentText(applicationContext.getString(R.string.transfers_paused_text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        runCatching { notifications().notify(PAUSED_ID, notification) }
    }

    private fun openApp(): PendingIntent? {
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName) ?: return null
        return PendingIntent.getActivity(applicationContext, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Android 15 stops a dataSync service after 6 hours a day and then refuses a new one while the app is
     * in the background; WorkManager would run the job again at once and mark it failed (seen 2026-11-09).
     * Ending quietly leaves the transfers queued, and the app starts them again when it is next on screen.
     */
    private suspend fun goForeground(): Boolean = try {
        setForeground(ForegroundInfo(NOTICE_ID, notification(null).setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        true
    } catch (_: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException is one.
        false
    }

    private fun notifications(): NotificationManager = applicationContext.getSystemService(NotificationManager::class.java)

    private fun builder(): NotificationCompat.Builder {
        notifications().createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.transfers_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL).setOnlyAlertOnce(true).setSilent(true)
    }

    /** How many and how far, never which files. Null before anything is known. */
    private fun notification(summary: QueueSummary?): NotificationCompat.Builder {
        val res = applicationContext.resources
        val count = summary?.total ?: 0
        val title = when (summary?.kind) {
            null -> if (summary == null) applicationContext.getString(R.string.transfers_starting) else res.getQuantityString(R.plurals.transfers_title_mixed, count, count)
            TransferKind.UPLOAD -> res.getQuantityString(R.plurals.transfers_title_upload, count, count)
            TransferKind.DOWNLOAD -> res.getQuantityString(R.plurals.transfers_title_download, count, count)
            TransferKind.CACHE -> res.getQuantityString(R.plurals.transfers_title_cache, count, count)
            TransferKind.OPS -> res.getString(R.string.transfers_title_ops)
        }
        val text = summary?.let {
            val done = res.getQuantityString(R.plurals.transfers_done_of, it.total, it.finished, it.total)
            if (it.size > 0) {
                done + " · " + applicationContext.getString(
                    R.string.transfers_amount,
                    Formatter.formatShortFileSize(applicationContext, it.done),
                    Formatter.formatShortFileSize(applicationContext, it.size),
                )
            } else {
                done
            }
        }
        return builder()
            .setSmallIcon(if (summary?.kind == TransferKind.UPLOAD) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, summary?.percent ?: 0, summary?.percent == null)
            .setContentIntent(openApp())
    }

    companion object {
        const val TAG = "coffer-transfer"

        /** The transfer that sends the queued operations, named after the first of them. */
        fun opsId(firstSeq: Long) = "$OPS_PREFIX$firstSeq"
        const val OPS_PREFIX = "ops-"
        private const val QUEUE = "transfers"
        private const val CHANNEL = "transfers"
        private const val NOTICE_ID = 0x7EAF
        private const val PAUSED_ID = 0x7EB0
        private const val NOTICE_EVERY_MS = 1_000L
        private const val FIRST_RETRY_MS = 30_000L
        private const val LAST_RETRY_MS = 5 * 60_000L

        // One queue a process (see doWork).
        private val running = Mutex()

        /**
         * New work in the queue: wakes the run that is going, or has WorkManager start one, after the one
         * that is ending if need be (see [QueueState]).
         */
        fun start(context: Context, registry: TransferRegistry) {
            val policy = when (registry.queue.added()) {
                QueueState.Start.NONE -> null
                QueueState.Start.ONE -> ExistingWorkPolicy.KEEP
                QueueState.Start.AFTER -> ExistingWorkPolicy.APPEND_OR_REPLACE
            }
            registry.wake.trySend(Unit)
            if (policy != null) {
                val request = OneTimeWorkRequestBuilder<TransferWorker>().setConstraints(transferConstraints()).addTag(TAG).build()
                WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, policy, request)
            }
        }

        /** The paused notice, once the user has seen to what it was about. */
        fun dismissPaused(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(PAUSED_ID)
        }
    }
}
