package pl.wspa.filum.data

import kotlin.math.max
import kotlin.math.min

/**
 * The budget for a `dataSync` foreground service (architecture §3.5).
 *
 * Android 15 grants such a service **six hours a day** over a rolling window. Once they
 * run out the system calls `onTimeout()` and kills the work at a moment of its choosing —
 * waiting for that is a mistake, because the transfer is cut off at a random point.
 *
 * So we track the budget ourselves and stop with a **hard margin**: after five hours, not
 * after five hours and fifty-five minutes. The work resumes at three levels, so stopping
 * halfway through a campaign costs nothing.
 */
class BudgetTracker(
    private val dao: BudgetDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** How much budget is left before the safe limit. Never below zero. */
    suspend fun remainingSeconds(): Long {
        val used = dao.secondsUsedSince(oldestRelevantBucket())
        return max(0, SAFE_LIMIT_SECONDS - used)
    }

    suspend fun usedSeconds(): Long = dao.secondsUsedSince(oldestRelevantBucket())

    /** Whether starting a cycle is worth it at all. A window under a minute wastes energy. */
    suspend fun hasRoomToStart(): Boolean = remainingSeconds() >= MINIMUM_USEFUL_SECONDS

    /**
     * Records time spent, spreading it across hourly buckets.
     *
     * The spreading matters: work running from 23:40 to 01:20 must not land in a single
     * bucket, or the rolling window would stop advancing correctly.
     */
    suspend fun record(startMillis: Long, endMillis: Long) {
        if (endMillis <= startMillis) return

        var cursor = startMillis
        while (cursor < endMillis) {
            val bucket = bucketOf(cursor)
            val bucketEnd = (bucket + 1) * BUCKET_MILLIS
            val segmentEnd = min(bucketEnd, endMillis)

            val seconds = (segmentEnd - cursor) / 1000
            if (seconds > 0) dao.add(bucket, seconds)

            cursor = segmentEnd
        }
    }

    /** Removes buckets outside the window. Without this the table would grow forever. */
    suspend fun prune() {
        dao.prune(oldestRelevantBucket())
    }

    private fun oldestRelevantBucket(): Long = bucketOf(clock()) - WINDOW_BUCKETS + 1

    companion object {
        const val BUCKET_MILLIS = 3_600_000L

        /** The system's rolling window: 24 hours. */
        const val WINDOW_BUCKETS = 24L

        /** The system limit. Stated for comparison — we never come close to it ourselves. */
        const val SYSTEM_LIMIT_SECONDS = 6L * 60 * 60

        /** Our limit, with an hour's margin for shutting the work down in a controlled way. */
        const val SAFE_LIMIT_SECONDS = 5L * 60 * 60

        /** Below a minute, raising a foreground service is not worth it. */
        const val MINIMUM_USEFUL_SECONDS = 60L

        fun bucketOf(epochMillis: Long): Long = Math.floorDiv(epochMillis, BUCKET_MILLIS)
    }
}
