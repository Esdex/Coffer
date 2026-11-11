package me.esdex.coffer.feature.browser.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.sync.ContentMismatchException
import me.esdex.coffer.core.sync.DownloadRunner
import me.esdex.coffer.core.sync.MissingPartException
import me.esdex.coffer.core.sync.NotEnoughSpaceException
import me.esdex.coffer.core.sync.SourceChangedException
import me.esdex.coffer.core.sync.TransferStage
import me.esdex.coffer.core.sync.UploadRunner
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.feature.browser.R
import java.io.File
import java.io.FileNotFoundException

/**
 * One upload or download, as a foreground job with a progress notification (SPEC §6). Holds only the
 * file's DEK, from [TransferRegistry]; without it (the process restarted) it ends quietly and the next
 * unlock starts it again. The notification never names the file: it can show on the lock screen.
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
) : CoroutineWorker(context, params) {

    private val id = inputData.getString(KEY_ID).orEmpty()
    private val isUpload = inputData.getString(KEY_KIND) == KIND_UPLOAD

    // The backend allows 3 transfers on the wire; this also keeps more than 3 encrypted parts
    // from piling up in the cache while they wait for a slot.
    override suspend fun doWork(): Result = try {
        registry.queued(id)
        registry.gate.withPermit { if (isUpload) upload() else download() }
    } catch (e: FloodWaitException) {
        // Honest FLOOD_WAIT (SPEC §6): wait what Telegram asked, then one more try through WorkManager.
        delay(e.seconds * 1000L)
        Result.retry()
    } catch (_: StorageException) {
        Result.retry()
    } finally {
        // First, so a late report cannot put the progress back.
        endProgress()
        registry.finished(id)
    }

    private suspend fun upload(): Result {
        val upload = index.uploads().find { it.uploadId == id } ?: return Result.success()
        val fileId = upload.fileId ?: return Result.success()
        val entry = registry.get(fileId) ?: return paused()
        if (!goForeground()) return paused()
        val storage = telegram.readyStorage() ?: return Result.retry()
        val runner = UploadRunner(sodium, storage, index, WorkFiles.upload(applicationContext))
        return try {
            val source = entry.source ?: return Result.success()
            runner.run(upload, entry.keys, ContentUriSource(applicationContext, source)) { done, total, stage -> report(done, total, stage) }
            registry.remove(fileId)
            // A file shared into Coffer was copied for this upload (SPEC §6, step 1); done with it.
            ShareInbox.deleteCopy(applicationContext, source)
            releaseFolderGrants(applicationContext, index)
            Result.success()
        } catch (_: SourceChangedException) {
            // Abandoned and out of the queue: its copy, if any, has nothing left to serve.
            entry.source?.let { ShareInbox.deleteCopy(applicationContext, it) }
            registry.remove(fileId)
            registry.fail(id, TransferRegistry.Failure.SOURCE_CHANGED)
            Result.failure()
        } catch (_: NotEnoughSpaceException) {
            registry.fail(id, TransferRegistry.Failure.NO_SPACE)
            Result.failure()
        } catch (_: FileNotFoundException) {
            registry.fail(id, TransferRegistry.Failure.SOURCE_GONE)
            Result.failure()
        } catch (_: SecurityException) {
            registry.fail(id, TransferRegistry.Failure.SOURCE_GONE)
            Result.failure()
        }
    }

    private suspend fun download(): Result {
        val download = index.downloads().find { it.downloadId == id } ?: return Result.success()
        val entry = registry.get(download.fileId) ?: return paused()
        if (!goForeground()) return paused()
        val storage = telegram.readyStorage() ?: return Result.retry()
        val target = entry.target?.let(Uri::parse)
        // An export whose target is not in memory yet waits for the unlock that provides it.
        if (download.sealedTarget != null && target == null) return paused()
        val open = entry.open
        val openFile = open?.let { File(File(OpenFiles.dir(applicationContext), id), it.name) }
        val failure = try {
            val output = if (target != null) {
                applicationContext.contentResolver.openOutputStream(target, "wt") ?: throw FileNotFoundException()
            } else {
                checkNotNull(openFile).also { it.parentFile?.mkdirs() }.outputStream()
            }
            output.use { DownloadRunner(sodium, storage, index) { part -> cache.keep(download.fileId, part, storage) }.run(download.fileId, entry.keys, it) { done, total -> report(done, total, TransferStage.MOVING) } }
            null
        } catch (_: ContentMismatchException) {
            TransferRegistry.Failure.CONTENT_MISMATCH
        } catch (_: MissingPartException) {
            TransferRegistry.Failure.MISSING_PART
        } catch (_: FileNotFoundException) {
            TransferRegistry.Failure.TARGET_FAILED
        } catch (_: me.esdex.coffer.core.crypto.AuthenticationException) {
            TransferRegistry.Failure.CONTENT_MISMATCH
        }
        if (failure != null) {
            // A file that did not verify must not be left looking like the real thing.
            if (target != null) runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, target) }
            openFile?.parentFile?.deleteRecursively()
            registry.fail(id, failure)
        } else if (openFile != null && open != null) {
            registry.readyToOpen(openFile, open.mime)
        }
        index.removeDownload(id)
        if (failure == null) registry.remove(download.fileId)
        releaseFolderGrants(applicationContext, index)
        return if (failure == null) Result.success() else Result.failure()
    }

    private var lastPercent = -1
    private var lastPhase: TransferRegistry.Phase? = null
    private var lastNotified = 0L
    private var ended = false

    /**
     * No progress is shown after this, and what was shown goes. TDLib reports progress from its own
     * thread and can do so after the transfer has returned: on a small file the last 100% then came
     * after WorkManager had taken the notice down, and stayed in the shade for good (seen 2026-11-11).
     */
    private fun endProgress() {
        val shown = synchronized(this) {
            ended = true
            lastPhase != null
        }
        // Only a progress notice: a paused one is meant to stay.
        if (shown) runCatching { notifications().cancel(id.hashCode()) }
    }

    @Synchronized
    private fun report(done: Long, total: Long, stage: TransferStage) {
        if (ended) return
        val phase = if (stage == TransferStage.PREPARING) TransferRegistry.Phase.PREPARING else TransferRegistry.Phase.MOVING
        val progress = TransferRegistry.Progress(done, total, phase)
        registry.progress(id, done, total, phase)
        val percent = progress.percent()
        // Android drops updates beyond about 5 a second, and on a fast network the percent moves faster.
        val now = SystemClock.elapsedRealtime()
        val changed = phase != lastPhase || (percent != lastPercent && (percent == 100 || now - lastNotified >= NOTIFY_INTERVAL_MS))
        // A late report after a stop would bring back a notice WorkManager has just taken down.
        if (changed && !isStopped) {
            lastPercent = percent
            lastPhase = phase
            lastNotified = now
            // Straight to the notification: setForegroundAsync also restarts the service, which posts it
            // twice milliseconds apart, and Android then drops one now and then (seen 2026-11-09).
            notifications().notify(id.hashCode(), notification(progress).build())
        }
    }

    /**
     * Ends without the transfer: the vault is locked after a restart, or Android refused the foreground
     * service. It stays in the index; this says so where the progress was, instead of leaving the user
     * to guess.
     */
    private fun paused(): Result {
        val notification = builder()
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(applicationContext.getString(if (isUpload) R.string.transfers_upload_paused else R.string.transfers_download_paused))
            .setContentText(applicationContext.getString(R.string.transfers_paused_text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        runCatching { notifications().notify(id.hashCode(), notification) }
        return Result.success()
    }

    private fun openApp(): PendingIntent? {
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName) ?: return null
        return PendingIntent.getActivity(applicationContext, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Android 15 stops a dataSync service after 6 hours a day and then refuses a new one while the app
     * is in the background; WorkManager runs the worker again at once and would mark it failed (seen
     * 2026-11-09). Ending quietly leaves the transfer in the index, and the app starts it again when it
     * is next on screen.
     */
    private suspend fun goForeground(): Boolean = try {
        setForeground(foregroundInfo(null))
        true
    } catch (_: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException is one.
        false
    }

    private fun notifications(): NotificationManager = applicationContext.getSystemService(NotificationManager::class.java)

    private fun builder(): NotificationCompat.Builder {
        val manager = notifications()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.transfers_channel), NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL).setOnlyAlertOnce(true)
    }

    private fun foregroundInfo(progress: TransferRegistry.Progress?): ForegroundInfo =
        ForegroundInfo(id.hashCode(), notification(progress).setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /**
     * Sizes, not the name: the notification can show on the lock screen. Null before anything is known.
     * Not ongoing by itself: the service keeps it in place while it runs, and one that outlives it can be
     * swiped away.
     */
    private fun notification(progress: TransferRegistry.Progress?): NotificationCompat.Builder {
        val moving = progress?.phase == TransferRegistry.Phase.MOVING
        val text = when {
            progress == null -> null
            !moving -> applicationContext.getString(R.string.transfers_preparing)
            else -> applicationContext.getString(
                R.string.transfers_amount,
                Formatter.formatShortFileSize(applicationContext, progress.done),
                Formatter.formatShortFileSize(applicationContext, progress.total),
            )
        }
        return builder()
            .setSmallIcon(if (isUpload) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(if (isUpload) R.string.transfers_uploading else R.string.transfers_downloading))
            .setContentText(text)
            .setProgress(100, progress?.percent() ?: 0, !moving)
            .setContentIntent(openApp())
    }

    companion object {
        const val TAG = "coffer-transfer"
        const val KEY_KIND = "kind"
        const val KEY_ID = "id"
        private const val KIND_UPLOAD = "upload"
        private const val KIND_DOWNLOAD = "download"
        private const val CHANNEL = "transfers"
        private const val NOTIFY_INTERVAL_MS = 1_000L

        /** The paused notice of a transfer the user cancelled; a running one's goes with its service. */
        fun dismiss(context: Context, id: String) {
            context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
        }

        fun uploadName(id: String) = "upload-$id"
        fun downloadName(id: String) = "download-$id"

        fun upload(id: String): Pair<String, OneTimeWorkRequest> =
            uploadName(id) to OneTimeWorkRequestBuilder<TransferWorker>().withTransferDefaults(KIND_UPLOAD, id).build()

        fun download(id: String): Pair<String, OneTimeWorkRequest> =
            downloadName(id) to OneTimeWorkRequestBuilder<TransferWorker>().withTransferDefaults(KIND_DOWNLOAD, id).build()
    }
}
