package dev.cloudwind.app

/**
 * Pure rules for the new-photo grace period ("wait before backing up") and proactive in-place edit detection.
 * Kept Android-free so every edge case is unit-tested on the JVM.
 */
object SettleWindow {
    const val DEFAULT_DELAY_SECS = 60

    /**
     * Seconds remaining before a newly captured or edited item should be backed up, or 0 if ready now.
     *
     * - Manual "Back up now" passes [ignoreDelay] = true and bypasses the grace window.
     * - Legacy DB rows with [dateModified] <= 0 or future clock skew ([dateModified] > [nowSecs])
     *   return 0 so a bad timestamp can never stall a backup forever.
     */
    fun remainingSecs(dateModified: Long, nowSecs: Long, delaySecs: Long, ignoreDelay: Boolean = false): Long {
        if (ignoreDelay || delaySecs <= 0L || dateModified <= 0L) return 0L
        val elapsed = nowSecs - dateModified
        if (elapsed < 0L) return 0L
        return (delaySecs - elapsed).coerceAtLeast(0L)
    }

    /** Formats a grace-period option for the UI chips. */
    fun label(secs: Int): String = when {
        secs <= 0 -> "Off"
        secs < 60 -> "${secs}s"
        else -> "${secs / 60}m"
    }
}

/** What [PhotoDb.upsertScanned] should do when MediaStore reports an item with a known media_id. */
enum class ScanAction {
    /** Nothing meaningful changed (or only backfilling date_modified on a pre-v4 row). */
    KEEP,
    /** Still pending, but size or date_modified changed (e.g. edited during grace window): refresh row & clear stale hash/stage. */
    REFRESH_PENDING,
    /** Already uploaded/synced/skipped, and edited in-place: re-queue as a fresh pending object. */
    REQUEUE_EDITED,
}

object EditDetector {
    /**
     * Decide whether a MediaStore update to an already-tracked row represents a real in-place edit.
     *
     * Pre-v4 rows have [existingDateModified] == 0: if [newSize] matches [existingSize], we only
     * backfill the timestamp instead of falsely re-uploading the whole library after an upgrade.
     */
    fun classify(
        existingState: String,
        existingSize: Long,
        existingDateModified: Long,
        newSize: Long,
        newDateModified: Long,
    ): ScanAction {
        val sizeChanged = newSize != existingSize
        val timeChanged = existingDateModified > 0L && newDateModified > 0L && newDateModified != existingDateModified
        if (!sizeChanged && !timeChanged) return ScanAction.KEEP
        return if (existingState == "pending") ScanAction.REFRESH_PENDING else ScanAction.REQUEUE_EDITED
    }
}
