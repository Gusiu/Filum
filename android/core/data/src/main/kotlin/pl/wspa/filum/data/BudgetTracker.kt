package pl.wspa.filum.data

import kotlin.math.max
import kotlin.math.min

/**
 * Budżet usługi pierwszoplanowej typu `dataSync` (§3.5 architektury).
 *
 * Android 15 daje takiej usłudze **sześć godzin na dobę** w oknie kroczącym. Po ich
 * wyczerpaniu system wywołuje `onTimeout()` i ubija pracę w nieokreślonym momencie —
 * czekanie na to jest błędem, bo transfer zostaje przerwany w losowym miejscu.
 *
 * Dlatego pilnujemy budżetu sami i kończymy z **twardym marginesem**: po pięciu
 * godzinach, nie po pięciu godzinach i pięćdziesięciu pięciu minutach. Praca jest
 * wznawialna na trzech poziomach, więc zakończenie w połowie kampanii nic nie kosztuje.
 */
class BudgetTracker(
    private val dao: BudgetDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Ile budżetu zostało do bezpiecznego limitu. Nigdy poniżej zera. */
    suspend fun remainingSeconds(): Long {
        val used = dao.secondsUsedSince(oldestRelevantBucket())
        return max(0, SAFE_LIMIT_SECONDS - used)
    }

    suspend fun usedSeconds(): Long = dao.secondsUsedSince(oldestRelevantBucket())

    /** Czy warto w ogóle zaczynać cykl. Krótsze okno niż minuta to strata energii. */
    suspend fun hasRoomToStart(): Boolean = remainingSeconds() >= MINIMUM_USEFUL_SECONDS

    /**
     * Zapisuje zużyty czas, rozdzielając go między kubełki godzinowe.
     *
     * Rozdzielenie jest istotne: praca trwająca od 23:40 do 01:20 nie może wpaść
     * w jeden kubełek, bo okno kroczące przestałoby się poprawnie przesuwać.
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

    /** Usuwa kubełki spoza okna. Bez tego tabela rosłaby w nieskończoność. */
    suspend fun prune() {
        dao.prune(oldestRelevantBucket())
    }

    private fun oldestRelevantBucket(): Long = bucketOf(clock()) - WINDOW_BUCKETS + 1

    companion object {
        const val BUCKET_MILLIS = 3_600_000L

        /** Okno kroczące systemu: 24 godziny. */
        const val WINDOW_BUCKETS = 24L

        /** Limit systemowy. Podany dla porównania — sami się do niego nie zbliżamy. */
        const val SYSTEM_LIMIT_SECONDS = 6L * 60 * 60

        /** Nasz limit z marginesem godziny na zamknięcie pracy w kontrolowany sposób. */
        const val SAFE_LIMIT_SECONDS = 5L * 60 * 60

        /** Poniżej minuty nie warto podnosić usługi pierwszoplanowej. */
        const val MINIMUM_USEFUL_SECONDS = 60L

        fun bucketOf(epochMillis: Long): Long = Math.floorDiv(epochMillis, BUCKET_MILLIS)
    }
}
