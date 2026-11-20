package me.esdex.coffer.feature.browser.transfer

import android.content.Context
import android.os.Process
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import me.esdex.coffer.core.index.VaultIndex
import java.util.concurrent.TimeUnit

/**
 * Deletes copies being edited once nothing is left to send and nobody wrote to them for an hour (see
 * [EditSessions.sweep]), also while the vault stays locked and after Android killed Coffer: the sweep
 * after a save ran only while the vault was open, and a copy waited, decrypted under its own name, for
 * the next unlock, days maybe (audit 3, DATA3-1). It needs no key: the index opens with the device's.
 */
class EditSessionsSweeper(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun index(): VaultIndex
    }

    override suspend fun doWork(): Result {
        val index = EntryPointAccessors.fromApplication(applicationContext, Parts::class.java).index()
        if (sweep(applicationContext, index)) schedule(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "CofferEditSessionsSweep"

        /** Sweeps now; true if any session is left, to be swept later. */
        suspend fun sweep(context: Context, index: VaultIndex): Boolean {
            val root = EditSessions.dir(context)
            EditSessions.sweep(root, index.uploads().mapTo(HashSet()) { it.uploadId }, System.currentTimeMillis(), Process.myPid())
            return EditSessions.all(root).isNotEmpty()
        }

        /** An hour after now, if no sweep is waiting already; each one schedules the next while sessions are left. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<EditSessionsSweeper>()
                .setInitialDelay(EditSessions.IDLE_MILLIS + SLACK_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        private const val SLACK_MILLIS = 60_000L
    }
}
