package dev.cloudwind.app

import android.content.Context

/**
 * Android 15 gives data-sync foreground services 6 hours in any 24-hour window. Past that the
 * system refuses to start another one. We keep our own ledger so long backlogs use foreground
 * time only while it's available, and otherwise fall back to ordinary background runs.
 */
class FgLedger(
    private val limitMs: Long = 5 * 3600_000L + 30 * 60_000L, // stay 30 min under the 6 h cap
    intervals: List<Pair<Long, Long>> = emptyList(),
) {
    private val items = intervals.toMutableList()

    private fun prune(now: Long) { items.removeAll { it.second < now - DAY } }

    /** Foreground milliseconds used in the 24 hours before `now`. */
    fun usedMs(now: Long): Long {
        prune(now)
        val from = now - DAY
        return items.sumOf { (s, e) -> (minOf(e, now) - maxOf(s, from)).coerceAtLeast(0) }
    }

    fun remainingMs(now: Long): Long = (limitMs - usedMs(now)).coerceAtLeast(0)

    fun record(startMs: Long, endMs: Long) { if (endMs > startMs) items += startMs to endMs }

    fun encode(): String = items.takeLast(100).joinToString(";") { "${it.first}:${it.second}" }

    companion object {
        const val DAY = 24 * 3600_000L
        fun decode(s: String?, limitMs: Long = 5 * 3600_000L + 30 * 60_000L): FgLedger = FgLedger(
            limitMs,
            s.orEmpty().split(';').mapNotNull {
                val p = it.split(':')
                if (p.size == 2) (p[0].toLongOrNull() ?: return@mapNotNull null) to (p[1].toLongOrNull() ?: return@mapNotNull null) else null
            },
        )
    }
}

/** Persists the ledger in SharedPreferences. */
class FgBudget(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("cloudwind_fg", Context.MODE_PRIVATE)
    private fun ledger() = FgLedger.decode(sp.getString("ledger", null))

    fun remainingMs(now: Long = System.currentTimeMillis()) = ledger().remainingMs(now)

    fun record(startMs: Long, endMs: Long) {
        val l = ledger()
        l.record(startMs, endMs)
        sp.edit().putString("ledger", l.encode()).apply()
    }
}
