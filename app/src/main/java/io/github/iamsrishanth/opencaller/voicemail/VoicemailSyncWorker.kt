package io.github.iamsrishanth.opencaller.voicemail

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Runs mailbox sync only while Android reports a network connection. */
class VoicemailSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result = try {
        VoicemailSyncRepository(applicationContext).synchronize()
        Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val ONE_TIME_NAME = "opencaller-voicemail-sync-now"
        private const val PERIODIC_NAME = "opencaller-voicemail-sync-periodic"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun configure(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                manager.cancelUniqueWork(ONE_TIME_NAME)
                manager.cancelUniqueWork(PERIODIC_NAME)
                return
            }
            manager.enqueueUniqueWork(
                ONE_TIME_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<VoicemailSyncWorker>()
                    .setConstraints(networkConstraint)
                    .build(),
            )
            manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<VoicemailSyncWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(networkConstraint)
                    .build(),
            )
        }

        suspend fun syncNow(context: Context): Int = VoicemailSyncRepository(context).synchronize()
    }
}
