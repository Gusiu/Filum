package pl.wspa.filum.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Atrapa DAO. Room udostępnia DAO jako interfejsy, więc logikę budżetu da się
 * przetestować bez bazy, bez emulatora i bez Robolectrica.
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
 * Budżet usługi pierwszoplanowej (§3.5 architektury).
 *
 * Błąd tutaj kończy się tym, że system ubija transfer w losowym momencie zamiast
 * pozwolić go zamknąć — dlatego liczymy sami i z zapasem.
 */
class BudgetTrackerTest {

    private val hour = BudgetTracker.BUCKET_MILLIS

    /** Równa godzina, żeby przeliczenia na kubełki były czytelne w asercjach. */
    private val poczatek = 1_756_400_000_000L / hour * hour

    private fun tracker(dao: BudgetDao, now: Long = poczatek) = BudgetTracker(dao) { now }

    @Test
    fun `swiezy budzet pozwala na pelne pieciogodzinne okno`() = runTest {
        val dao = FakeBudgetDao()

        assertEquals(BudgetTracker.SAFE_LIMIT_SECONDS, tracker(dao).remainingSeconds())
        assertTrue(tracker(dao).hasRoomToStart())
    }

    @Test
    fun `limit wlasny jest nizszy od systemowego`() {
        // Margines istnieje po to, żeby zdążyć zamknąć transfer, zanim system
        // wywoła onTimeout i ubije pracę w nieokreślonym miejscu.
        assertTrue(BudgetTracker.SAFE_LIMIT_SECONDS < BudgetTracker.SYSTEM_LIMIT_SECONDS)
        assertEquals(3600, BudgetTracker.SYSTEM_LIMIT_SECONDS - BudgetTracker.SAFE_LIMIT_SECONDS)
    }

    @Test
    fun `zuzyty czas pomniejsza pozostaly budzet`() = runTest {
        val dao = FakeBudgetDao()
        val start = poczatek

        tracker(dao, start).record(start, start + 2 * hour)

        val remaining = tracker(dao, start + 2 * hour).remainingSeconds()
        assertEquals(BudgetTracker.SAFE_LIMIT_SECONDS - 2 * 3600, remaining)
    }

    @Test
    fun `praca przez polnoc rozklada sie na kubelki godzinowe`() = runTest {
        // Cykl nocny trwa od 23:40 do 01:20. Wrzucenie całości do jednego kubełka
        // zepsułoby przesuwanie się okna kroczącego.
        val dao = FakeBudgetDao()
        val start = poczatek + 40 * 60_000
        val end = start + 100 * 60_000

        tracker(dao).record(start, end)

        assertEquals(3, dao.buckets.size)
        assertEquals(20 * 60L, dao.buckets[BudgetTracker.bucketOf(start)])
        assertEquals(60 * 60L, dao.buckets[BudgetTracker.bucketOf(start) + 1])
        assertEquals(20 * 60L, dao.buckets[BudgetTracker.bucketOf(start) + 2])
        assertEquals(100 * 60L, dao.buckets.values.sum())
    }

    @Test
    fun `okno jest kroczace, a nie dobowe`() = runTest {
        // Cztery godziny pracy od 00:00 do 04:00. O 25:00 wypadły z okna dopiero
        // dwie pierwsze — pozostałe dwie wciąż się liczą. To jest różnica między
        // oknem kroczącym a resetem o północy i decyduje o tym, czy nocny cykl
        // ruszy, czy zostanie ubity w połowie.
        val dao = FakeBudgetDao()
        val wczoraj = poczatek

        tracker(dao, wczoraj).record(wczoraj, wczoraj + 4 * hour)

        assertEquals(2 * 3600, tracker(dao, wczoraj + 25 * hour).usedSeconds())
    }

    @Test
    fun `zuzycie starsze niz cale okno przestaje sie liczyc`() = runTest {
        val dao = FakeBudgetDao()
        val wczoraj = poczatek

        tracker(dao, wczoraj).record(wczoraj, wczoraj + 4 * hour)

        val teraz = wczoraj + 30 * hour
        assertEquals(0, tracker(dao, teraz).usedSeconds())
        assertEquals(BudgetTracker.SAFE_LIMIT_SECONDS, tracker(dao, teraz).remainingSeconds())
    }

    @Test
    fun `wyczerpany budzet nie schodzi ponizej zera`() = runTest {
        val dao = FakeBudgetDao()
        val start = poczatek

        tracker(dao, start).record(start, start + 10 * hour)

        val t = tracker(dao, start + 10 * hour)
        assertEquals(0, t.remainingSeconds())
        assertFalse(t.hasRoomToStart())
    }

    @Test
    fun `resztka ponizej minuty nie uzasadnia podniesienia uslugi`() = runTest {
        val dao = FakeBudgetDao()
        val start = poczatek
        val prawieCaly = BudgetTracker.SAFE_LIMIT_SECONDS - 30

        dao.add(BudgetTracker.bucketOf(start), prawieCaly)

        val t = tracker(dao, start)
        assertEquals(30, t.remainingSeconds())
        assertFalse(t.hasRoomToStart())
    }

    @Test
    fun `sprzatanie usuwa kubelki spoza okna`() = runTest {
        val dao = FakeBudgetDao()
        val stary = poczatek
        dao.add(BudgetTracker.bucketOf(stary), 600)
        dao.add(BudgetTracker.bucketOf(stary + 30 * hour), 600)

        tracker(dao, stary + 30 * hour).prune()

        assertEquals(1, dao.buckets.size)
    }

    @Test
    fun `zapis pustego albo cofnietego przedzialu jest pomijany`() = runTest {
        val dao = FakeBudgetDao()

        tracker(dao).record(poczatek, poczatek)
        tracker(dao).record(poczatek + hour, poczatek)

        assertTrue(dao.buckets.isEmpty())
    }

    @Test
    fun `kubelki sa ciagle takze przed epoka`() {
        // floorDiv, nie dzielenie całkowite: przy ujemnych znacznikach czasu zwykłe
        // dzielenie zaokrągla ku zeru i tworzy zdublowany kubełek wokół epoki.
        assertEquals(-1, BudgetTracker.bucketOf(-1))
        assertEquals(0, BudgetTracker.bucketOf(0))
        assertEquals(-1, BudgetTracker.bucketOf(-hour))
        assertEquals(-2, BudgetTracker.bucketOf(-hour - 1))
    }
}
