package dev.cloudwind.app

/** Decides when to tell the user "backups have stopped". Pure, so it's unit-tested. */
object BackupHealth {
    const val STALE_AFTER_SECS = 24 * 3600L

    /**
     * Backups are stale when something is waiting to upload but no pass has succeeded for a day.
     * `pairedAt` stops a freshly paired phone from looking stale before its first run.
     */
    fun isStale(pending: Int, lastSuccess: Long, pairedAt: Long, now: Long): Boolean {
        if (pending <= 0) return false
        val reference = maxOf(lastSuccess, pairedAt)
        return reference > 0 && now - reference > STALE_AFTER_SECS
    }

    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun ago(secs: Long): String = when {
        secs < 60 -> "just now"
        secs < 3600 -> "${secs / 60} min ago"
        secs < 86400 -> "${secs / 3600} h ago"
        else -> "${secs / 86400} day${if (secs / 86400 == 1L) "" else "s"} ago"
    }
}
