package pl.wspa.filum.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A fake DAO. Room exposes DAOs as interfaces, so the budget logic can be tested
 * without a database, without an emulator and without Robolectric.
 */
private class FakeBudgetDao : BudgetDao {

    val buckets = linkedMapOf<Long, Long>()

    override suspend fun secondsUsedSince(since: Long): Long =
        buckets.filterKeys { it >= since }.values.sum()

    override suspend fun add(bucket: Long, seconds: Long) {
        buckets[bucket] = (buckets[bucket] ?: 0) + seconds
    }

    override suspend fun prune(before: Long) {
        buckets.keys.filter { it < before }.forEach { buckets.remove(it) }
    }
}

/**
 * The foreground service budget (architecture §3.5).
 *
 * A mistake here ends with the system killing the transfer at a random moment instead
 * of letting it close - which is why we count ourselves, and with a margin.
 */
class BudgetTrackerTest {

    private val hour = BudgetTracker.BUCKET_MILLIS

    /** A whole hour, so that bucket arithmetic reads clearly in the assertions. */
    private val origin = 1_756_400_000_000L / hour * hour

    private fun tracker(dao: BudgetDao, now: Long = origin) = BudgetTracker(dao) { now }

    @Test
    fun `a fresh budget allows the full five-hour window`() = runTest {
        val dao = FakeBudgetDao()

        assertEquals(BudgetTracker.SAFE_LIMIT_SECONDS, tracker(dao).remainingSeconds())
        assertTrue(tracker(dao).hasRoomToStart())
    }

    @Test
    fun `our own limit sits below the system one`() {
        // The margin exists so there is time to close the transfer before the system
        // calls onTimeout and kills the work at an arbitrary point.
        assertTrue(BudgetTracker.SAFE_LIMIT_SECONDS < BudgetTracker.SYSTEM_LIMIT_SECONDS)
        assertEquals(3600, BudgetTracker.SYSTEM_LIMIT_SECONDS - BudgetTracker.SAFE_LIMIT_SECONDS)
    }

    @Test
    fun `time spent reduces the remaining budget`() = runTest {
        val dao = FakeBudgetDao()
        val start = origin

        tracker(dao, start).record(start, start + 2 * hour)

        val remaining = tracker(dao, start + 2 * hour).remainingSeconds()
        assertEquals(BudgetTracker.SAFE_LIMIT_SECONDS - 2 * 3600, remaining)
    }

    @Test
    fun `work spanning midnight spreads across hourly buckets`() = runTest {
        // A night cycle runs from 23:40 to 01:20. Dropping all of it into one bucket
        // would break the way the rolling window advances.
        val dao = FakeBudgetDao()
        val start = origin + 40 * 60_000
        val end = start + 100 * 60_000

        tracker(dao).record(start, end)

        assertEquals(3, dao.buckets.size)
        assertEquals(20 * 60L, dao.buckets[BudgetTracker.bucketOf(start)])
        assertEquals(60 * 60L, dao.buckets[BudgetTracker.bucketOf(start) + 1])
        assertEquals(20 * 60L, dao.buckets[BudgetTracker.bucketOf(start) + 2])
        assertEquals(100 * 60L, dao.buckets.values.sum())
    }

    @Test
    fun `the window rolls, it is not a calendar day`() = runTest {
        // Four hours of work from 00:00 to 04:00. By 25:00 only the first two have
        // fallen out of the window - the other two still count. That is the difference
        // between a rolling window and a reset at midnight, and it decides whether the
        // night cycle starts at all or gets killed halfway.
        val dao = FakeBudgetDao()
        val yesterday = origin

        tracker(dao, yesterday).record(yesterday, yesterday + 4 * hour)

        assertEquals(2 * 3600, tracker(dao, yesterday + 25 * hour).usedSeconds())
    }

    @Test
    fun `usage older than the whole window stops counting`() = runTest {
        val dao = FakeBudgetDao()
        val yesterday = origin

        tracker(dao, yesterday).record(yesterday, yesterday + 4 * hour)

        val now = yesterday + 30 * hour
        assertEquals(0, tracker(dao, now).usedSeconds())
        assertEquals(BudgetTracker.SAFE_LIMIT_SECONDS, tracker(dao, now).remainingSeconds())
    }

    @Test
    fun `an exhausted budget does not go below zero`() = runTest {
        val dao = FakeBudgetDao()
        val start = origin

        tracker(dao, start).record(start, start + 10 * hour)

        val t = tracker(dao, start + 10 * hour)
        assertEquals(0, t.remainingSeconds())
        assertFalse(t.hasRoomToStart())
    }

    @Test
    fun `a remainder under a minute does not justify raising the service`() = runTest {
        val dao = FakeBudgetDao()
        val start = origin
        val prawieCaly = BudgetTracker.SAFE_LIMIT_SECONDS - 30

        dao.add(BudgetTracker.bucketOf(start), prawieCaly)

        val t = tracker(dao, start)
        assertEquals(30, t.remainingSeconds())
        assertFalse(t.hasRoomToStart())
    }

    @Test
    fun `cleanup removes buckets outside the window`() = runTest {
        val dao = FakeBudgetDao()
        val old = origin
        dao.add(BudgetTracker.bucketOf(old), 600)
        dao.add(BudgetTracker.bucketOf(old + 30 * hour), 600)

        tracker(dao, old + 30 * hour).prune()

        assertEquals(1, dao.buckets.size)
    }

    @Test
    fun `recording an empty or backwards interval is skipped`() = runTest {
        val dao = FakeBudgetDao()

        tracker(dao).record(origin, origin)
        tracker(dao).record(origin + hour, origin)

        assertTrue(dao.buckets.isEmpty())
    }

    @Test
    fun `the buckets stay continuous before the epoch too`() {
        // floorDiv, not integer division: with negative timestamps ordinary division
        // rounds towards zero and creates a duplicated bucket around the epoch.
        assertEquals(-1, BudgetTracker.bucketOf(-1))
        assertEquals(0, BudgetTracker.bucketOf(0))
        assertEquals(-1, BudgetTracker.bucketOf(-hour))
        assertEquals(-2, BudgetTracker.bucketOf(-hour - 1))
    }
}
