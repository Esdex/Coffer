package me.esdex.coffer.feature.browser.transfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Deletes decrypted copies handed to another app once their time is up, even if Android has killed
 * Coffer meanwhile: the in-app timer dies with the process, and the copies would otherwise wait for the
 * next start, possibly days (audit 1, M11). WorkManager starts the process again for it.
 */
class OpenFilesSweeper(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A copy younger than its time, or still being written, gets a sweep of its own later.
        if (OpenFiles.sweep(OpenFiles.dir(applicationContext), System.currentTimeMillis())) schedule(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "CofferOpenFilesSweep"

        /** After the newest copy's time is up; a newer copy moves the sweep later. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OpenFilesSweeper>()
                .setInitialDelay(OpenFiles.LIFETIME_MILLIS + SLACK_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private const val SLACK_MILLIS = 60_000L
    }
}
