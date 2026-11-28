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
    private const val SETTLE = "cloudwind-settle"
    const val TAG_TRIGGER = "cloudwind-trigger"
    const val TAG_MANUAL = "cloudwind-manual"

    /**
     * Jobs no longer carry Wi-Fi/charging rules: the runner applies them per file, so small photos still go
     * and direct (LAN) transfers work even on a network with no internet. The only constraint left is the
     * new-photo trigger, debounced briefly so camera HDR/IS_PENDING writes finish before we scan.
     */
    private fun constraints(@Suppress("UNUSED_PARAMETER") settings: Settings, trigger: Boolean = false, @Suppress("UNUSED_PARAMETER") ignoreRules: Boolean = false) =
        Constraints.Builder().apply {
            setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            if (trigger) {
                addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                setTriggerContentUpdateDelay(3, TimeUnit.SECONDS)
                setTriggerContentMaxDelay(15, TimeUnit.SECONDS)
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

    /**
     * One-shot work that fires when the media library changes. Only the trigger job itself appends its
     * successor (it is running, so the new one waits behind it). Everyone else uses KEEP: appending from
     * outside would grow a chain of waiting trigger jobs by one every time.
     */
    fun armTrigger(context: Context, fromTriggerJob: Boolean = false) {
        val s = Settings(context)
        WorkManager.getInstance(context).enqueueUniqueWork(
            ON_CHANGE, if (fromTriggerJob) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(s, trigger = true))
                .addTag(TAG_TRIGGER)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** "Back up now": runs immediately on any connection, bypassing the new-photo grace period. */
    fun backUpNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(Settings(context), ignoreRules = true))
                .addTag(TAG_MANUAL)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build(),
        )
    }

    /** Wake right after the earliest new-photo grace period expires so items never wait 15 minutes. */
    fun scheduleAfterDelay(context: Context, delaySecs: Long) {
        val secs = (delaySecs + 2L).coerceIn(3L, 3600L)
        WorkManager.getInstance(context).enqueueUniqueWork(
            SETTLE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(secs, TimeUnit.SECONDS)
                .setConstraints(constraints(Settings(context)))
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
        wm.cancelUniqueWork(PERIODIC); wm.cancelUniqueWork(ON_CHANGE); wm.cancelUniqueWork(NOW); wm.cancelUniqueWork(CONTINUE); wm.cancelUniqueWork(SETTLE)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = Notifications.foreground(applicationContext, "Backing up photos…", 0, 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val fromTrigger = tags.contains(Scheduler.TAG_TRIGGER)
        try {
            // One pass at a time. Periodic, new-photo, "back up now" and continuation jobs can all fire
            // together; two passes would stage the same file twice and clean up each other's temp files.
            if (!SyncRunner.running.compareAndSet(false, true)) {
                SyncRunner.rerunRequested = true // the running pass will queue one more when it finishes
                return@withContext Result.success()
            }
            try {
                runPass(ctx)
            } finally {
                SyncRunner.running.set(false)
                if (SyncRunner.rerunRequested) { SyncRunner.rerunRequested = false; Scheduler.continueSoon(ctx) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never let an unexpected error kill the schedule; show it and try again later.
            Settings(ctx).lastError = "Unexpected error: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            Result.retry()
        } finally {
            Scheduler.armTrigger(ctx, fromTriggerJob = fromTrigger)
        }
    }

    private suspend fun runPass(ctx: Context): Result {
        val runner = SyncRunner(ctx)
        val budget = FgBudget(ctx)
        val settings = Settings(ctx)

        // Foreground time is rationed (Android 15 allows 6 h per 24 h). Use it only when something can
        // actually be sent (not for files that are just waiting for Wi-Fi or home) and allowance is left.
        var fgStart = 0L
        var budgetMs = 8 * 60_000L // plain background jobs get about 10 minutes
        val sendable = runner.pendingCount() - settings.waitingCount
        if (sendable > 0 && budget.remainingMs() > 20 * 60_000L) {
            try {
                setForeground(getForegroundInfo())
                fgStart = System.currentTimeMillis()
                budgetMs = minOf(30 * 60_000L, budget.remainingMs() - 5 * 60_000L)
            } catch (_: Exception) { /* not allowed from the background right now: carry on without it */ }
        }

        val ignoreDelay = tags.contains(Scheduler.TAG_MANUAL)
        val outcome = try {
            runner.run(
                budgetMs = budgetMs,
                ignoreDelay = ignoreDelay,
                onProgress = { done, total, name ->
                    if (fgStart != 0L) try { setForegroundAsync(Notifications.foreground(ctx, name, done, total)) } catch (_: Exception) {}
                },
                isStopped = { isStopped }, // also true when the OS ends a foreground service early
            )
        } finally {
            if (fgStart != 0L) budget.record(fgStart, System.currentTimeMillis())
        }

        return when (outcome) {
            is SyncRunner.Outcome.Retry -> Result.retry()
            is SyncRunner.Outcome.Paused -> {
                // Out of time with work left: keep going in a fresh run. If the OS stopped us, it reschedules.
                if (outcome.deadlineHit) Scheduler.continueSoon(ctx)
                Result.success()
            }
            is SyncRunner.Outcome.Done -> {
                when {
                    outcome.rescanFound -> Scheduler.continueSoon(ctx)
                    outcome.nextSettleSecs != null -> Scheduler.scheduleAfterDelay(ctx, outcome.nextSettleSecs)
                }
                Result.success()
            }
            else -> Result.success()
        }
    }
}
