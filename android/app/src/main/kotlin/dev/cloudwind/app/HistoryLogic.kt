package dev.cloudwind.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One backed-up item recorded in the persistent History log (survives phone cleanup). */
data class HistoryItem(
    val id: Long,
    val mediaId: Long,
    val docUri: String?,
    val objectId: String,
    val name: String,
    val relPath: String,
    val size: Long,
    val isVideo: Boolean,
    val durationMs: Long = 0L,
    /** "direct" (home Wi-Fi straight to PC) or "cloud" (uploaded via encrypted relay). */
    val route: String,
    /** "pc" (verified on PC) or "cloud" (awaiting PC pickup). */
    val status: String,
    /** Epoch seconds when the transfer finished. */
    val transferredAt: Long,
    /** Epoch seconds when confirmed on PC, or null while still in cloud. */
    val verifiedAt: Long? = null,
    /** True once the local phone copy was removed via Free Up Space or Remove from Phone. */
    val localRemoved: Boolean = false,
    /** Filename inside filesDir/thumbs/ (e.g. "<objectId>.webp"), or null if unavailable/pruned. */
    val thumbFile: String? = null,
) {
    val onPc: Boolean get() = status == "pc"
    val inCloud: Boolean get() = status != "pc"
}

/**
 * Pure history grouping, retention, and formatting helpers (Android-free, JVM unit-tested).
 */
object HistoryLogic {
    /** Rolling cap on cached WebP thumbnails (~6-10 KB each => ~3-5 MB max on disk). */
    const val MAX_THUMBS = 500

    val GROUP_ORDER = listOf("Today", "Yesterday", "Earlier This Week", "Earlier")

    /**
     * Bucket a transfer timestamp into "Today", "Yesterday", "Earlier This Week", or "Earlier",
     * using [zoneOffsetSecs] (local UTC offset in seconds).
     */
    fun dayGroup(epochSecs: Long, nowSecs: Long, zoneOffsetSecs: Int = defaultZoneOffsetSecs(nowSecs)): String {
        if (epochSecs <= 0L || nowSecs <= 0L) return "Earlier"
        val itemDay = Math.floorDiv(epochSecs + zoneOffsetSecs, 86_400L)
        val nowDay = Math.floorDiv(nowSecs + zoneOffsetSecs, 86_400L)
        val delta = nowDay - itemDay
        return when {
            delta <= 0L -> "Today"
            delta == 1L -> "Yesterday"
            delta in 2L..6L -> "Earlier This Week"
            else -> "Earlier"
        }
    }

    /** Formats a video duration in milliseconds as "0:24" or "1:04:12", or null if <= 0. */
    fun formatDuration(durationMs: Long): String? {
        if (durationMs <= 0L) return null
        val totalSecs = (durationMs / 1000L).coerceAtLeast(1L)
        val hrs = totalSecs / 3600L
        val mins = (totalSecs % 3600L) / 60L
        val secs = totalSecs % 60L
        return if (hrs > 0L) {
            "%d:%02d:%02d".format(Locale.US, hrs, mins, secs)
        } else {
            "%d:%02d".format(Locale.US, mins, secs)
        }
    }

    /**
     * Given cached thumbnail filenames ordered newest-first, returns the overflow filenames beyond
     * [maxKeep] that should be deleted from disk.
     */
    fun thumbsToPrune(orderedNewestFirst: List<String>, maxKeep: Int = MAX_THUMBS): List<String> {
        val cap = maxKeep.coerceAtLeast(0)
        if (orderedNewestFirst.size <= cap) return emptyList()
        return orderedNewestFirst.subList(cap, orderedNewestFirst.size)
    }

    /** Human timestamp for the History inspector sheet (e.g. "Today, 9:45 AM" or "Nov 24, 6:12 PM"). */
    fun formatDateTime(
        epochSecs: Long,
        nowSecs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        if (epochSecs <= 0L) return "Backed up"
        if (nowSecs > 0L && nowSecs - epochSecs in 0..59) return "Just now"
        val offsetSecs = zone.getOffset(nowSecs * 1000L) / 1000
        val group = dayGroup(epochSecs, nowSecs, offsetSecs)
        val date = Date(epochSecs * 1000L)
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US).apply { timeZone = zone }
        return when (group) {
            "Today" -> "Today, ${timeFmt.format(date)}"
            "Yesterday" -> "Yesterday, ${timeFmt.format(date)}"
            "Earlier This Week" -> {
                val dow = SimpleDateFormat("EEEE", Locale.US).apply { timeZone = zone }.format(date)
                "$dow, ${timeFmt.format(date)}"
            }
            else -> {
                val d = SimpleDateFormat("MMM d", Locale.US).apply { timeZone = zone }.format(date)
                "$d, ${timeFmt.format(date)}"
            }
        }
    }

    fun defaultZoneOffsetSecs(nowSecs: Long): Int =
        TimeZone.getDefault().getOffset(nowSecs * 1000L) / 1000
}
