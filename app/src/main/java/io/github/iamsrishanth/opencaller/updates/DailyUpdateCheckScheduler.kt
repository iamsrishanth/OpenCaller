package io.github.iamsrishanth.opencaller.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal fun interface DailyCheckScheduler {
    fun setEnabled(enabled: Boolean)
}

internal object DailyUpdateCheckSchedule {
    const val UNIQUE_WORK_NAME = "open-caller-daily-update-check"
    const val INTERVAL_HOURS = 24L
    const val REQUIRES_NETWORK = true
}

internal class WorkManagerDailyCheckScheduler(context: Context) : DailyCheckScheduler {
    private val appContext = context.applicationContext

    override fun setEnabled(enabled: Boolean) {
        val workManager = WorkManager.getInstance(appContext)
        if (!enabled) {
            workManager.cancelUniqueWork(DailyUpdateCheckSchedule.UNIQUE_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder().apply {
            if (DailyUpdateCheckSchedule.REQUIRES_NETWORK) {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()
        val request = PeriodicWorkRequestBuilder<DailyUpdateCheckWorker>(
            DailyUpdateCheckSchedule.INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            DailyUpdateCheckSchedule.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

class DailyUpdateCheckWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = try {
        UpdatesRepository(applicationContext).checkForUpdates()
        // The repository saves each source independently. A transient failure waits for the next
        // daily interval instead of scheduling an earlier retry.
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Keep the periodic request alive; do not retry before the next 24-hour interval.
        Result.success()
    }
}
