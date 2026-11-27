package dev.cloudwind.app

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Scheduler {
    private const val PERIODIC = "cloudwind-periodic"
    private const val ON_CHANGE = "cloudwind-on-change"
    private const val NOW = "cloudwind-now"

    private fun constraints(settings: Settings, trigger: Boolean = false, ignoreRules: Boolean = false) =
        Constraints.Builder().apply {
            setRequiredNetworkType(if (settings.wifiOnly && !ignoreRules) NetworkType.UNMETERED else NetworkType.CONNECTED)
            setRequiresCharging(settings.chargingOnly && !ignoreRules)
            if (trigger) {
                addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            }
        }.build()

    /** (Re)arm the periodic safety net and the "new photo appeared" trigger. Safe to call often. */
    fun schedule(context: Context) {
        val s = Settings(context)
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints(s))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
        armTrigger(context)
    }

    /** One-shot work that fires when the media library changes. SyncWorker re-arms it after each run. */
    fun armTrigger(context: Context) {
        val s = Settings(context)
        WorkManager.getInstance(context).enqueueUniqueWork(
            ON_CHANGE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(s, trigger = true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** "Back up now": runs immediately on any connection, ignoring the Wi-Fi/charging rules. */
    fun backUpNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(Settings(context), ignoreRules = true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(ON_CHANGE); wm.cancelUniqueWork(NOW)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = Notifications.foreground(applicationContext, "Backing up photos…", 0, 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val runner = SyncRunner(applicationContext)
        // Show the foreground notification only when there is real work, so idle checks stay invisible.
        if (runner.pendingCount() > 0) {
            try { setForeground(getForegroundInfo()) } catch (_: Exception) { /* not allowed from background: carry on */ }
        }
        val outcome = runner.run(
            onProgress = { done, total, name ->
                try { setForegroundAsync(Notifications.foreground(applicationContext, name, done, total)) } catch (_: Exception) {}
            },
            isStopped = { isStopped },
        )
        Scheduler.armTrigger(applicationContext)
        when (outcome) {
            is SyncRunner.Outcome.Retry -> Result.retry()
            else -> Result.success()
        }
    }
}
