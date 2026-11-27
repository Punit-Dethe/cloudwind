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
    private const val CONTINUE = "cloudwind-continue"

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
            ON_CHANGE, ExistingWorkPolicy.APPEND_OR_REPLACE,
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

    /** A pass ran out of time with uploads left: queue another right behind it (same Wi-Fi/charging rules). */
    fun continueSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CONTINUE, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(Settings(context)))
                .build(),
        )
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(ON_CHANGE); wm.cancelUniqueWork(NOW); wm.cancelUniqueWork(CONTINUE)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = Notifications.foreground(applicationContext, "Backing up photos…", 0, 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val runner = SyncRunner(ctx)
        val budget = FgBudget(ctx)

        // Foreground time is rationed (Android 15 allows 6 h per 24 h). Use it only for real work and
        // only while there is allowance left; otherwise run as an ordinary background job.
        var fgStart = 0L
        var budgetMs = 8 * 60_000L // plain background jobs get about 10 minutes
        if (runner.pendingCount() > 0 && budget.remainingMs() > 20 * 60_000L) {
            try {
                setForeground(getForegroundInfo())
                fgStart = System.currentTimeMillis()
                budgetMs = minOf(30 * 60_000L, budget.remainingMs() - 5 * 60_000L)
            } catch (_: Exception) { /* not allowed from the background right now: carry on without it */ }
        }

        val outcome = try {
            runner.run(
                budgetMs = budgetMs,
                onProgress = { done, total, name ->
                    if (fgStart != 0L) try { setForegroundAsync(Notifications.foreground(ctx, name, done, total)) } catch (_: Exception) {}
                },
                isStopped = { isStopped }, // also true when the OS ends a foreground service early
            )
        } finally {
            if (fgStart != 0L) budget.record(fgStart, System.currentTimeMillis())
        }

        Scheduler.armTrigger(ctx)
        when (outcome) {
            is SyncRunner.Outcome.Retry -> Result.retry()
            is SyncRunner.Outcome.Paused -> {
                // Out of time with work left: keep going in a fresh run. If the OS stopped us, it reschedules.
                if (outcome.deadlineHit) Scheduler.continueSoon(ctx)
                Result.success()
            }
            else -> Result.success()
        }
    }
}
