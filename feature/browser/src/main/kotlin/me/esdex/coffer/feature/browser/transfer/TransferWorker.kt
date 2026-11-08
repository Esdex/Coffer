package me.esdex.coffer.feature.browser.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
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
) : CoroutineWorker(context, params) {

    private val id = inputData.getString(KEY_ID).orEmpty()
    private val isUpload = inputData.getString(KEY_KIND) == KIND_UPLOAD

    // The backend allows 3 transfers on the wire; this also keeps more than 3 encrypted parts
    // from piling up in the cache while they wait for a slot.
    override suspend fun doWork(): Result = try {
        registry.gate.withPermit { if (isUpload) upload() else download() }
    } catch (e: FloodWaitException) {
        // Honest FLOOD_WAIT (SPEC §6): wait what Telegram asked, then one more try through WorkManager.
        delay(e.seconds * 1000L)
        Result.retry()
    } catch (_: StorageException) {
        Result.retry()
    } finally {
        registry.finished(id)
    }

    private suspend fun upload(): Result {
        val upload = index.uploads().find { it.uploadId == id } ?: return Result.success()
        val fileId = upload.fileId ?: return Result.success()
        val entry = registry.get(fileId) ?: return Result.success()
        setForeground(foregroundInfo(0))
        val storage = telegram.readyStorage() ?: return Result.retry()
        val runner = UploadRunner(sodium, storage, index, File(applicationContext.cacheDir, "upload"))
        return try {
            val source = entry.source ?: return Result.success()
            runner.run(upload, entry.keys, ContentUriSource(applicationContext, source)) { done, total -> report(done, total) }
            registry.remove(fileId)
            Result.success()
        } catch (_: SourceChangedException) {
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
        val entry = registry.get(download.fileId) ?: return Result.success()
        setForeground(foregroundInfo(0))
        val storage = telegram.readyStorage() ?: return Result.retry()
        val target = entry.target?.let(Uri::parse)
        // An export whose target is not in memory yet waits for the unlock that provides it.
        if (download.sealedTarget != null && target == null) return Result.success()
        val open = entry.open
        val openFile = open?.let { File(File(OpenFiles.dir(applicationContext), id), it.name) }
        val failure = try {
            val output = if (target != null) {
                applicationContext.contentResolver.openOutputStream(target, "wt") ?: throw FileNotFoundException()
            } else {
                checkNotNull(openFile).also { it.parentFile?.mkdirs() }.outputStream()
            }
            output.use { DownloadRunner(sodium, storage, index).run(download.fileId, entry.keys, it) { done, total -> report(done, total) } }
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
        return if (failure == null) Result.success() else Result.failure()
    }

    private var lastPercent = -1

    private fun report(done: Long, total: Long) {
        registry.progress(id, done, total)
        val percent = if (total > 0) (done * 100 / total).toInt() else 100
        if (percent != lastPercent) {
            lastPercent = percent
            setForegroundAsync(foregroundInfo(percent))
        }
    }

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.transfers_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(if (isUpload) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(if (isUpload) R.string.transfers_uploading else R.string.transfers_downloading))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "coffer-transfer"
        const val KEY_KIND = "kind"
        const val KEY_ID = "id"
        private const val KIND_UPLOAD = "upload"
        private const val KIND_DOWNLOAD = "download"
        private const val CHANNEL = "transfers"

        fun uploadName(id: String) = "upload-$id"
        fun downloadName(id: String) = "download-$id"

        fun upload(id: String): Pair<String, OneTimeWorkRequest> =
            uploadName(id) to OneTimeWorkRequestBuilder<TransferWorker>().withTransferDefaults(KIND_UPLOAD, id).build()

        fun download(id: String): Pair<String, OneTimeWorkRequest> =
            downloadName(id) to OneTimeWorkRequestBuilder<TransferWorker>().withTransferDefaults(KIND_DOWNLOAD, id).build()
    }
}
