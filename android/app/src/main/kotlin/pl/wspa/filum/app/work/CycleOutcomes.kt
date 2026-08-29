package pl.wspa.filum.app.work

import pl.wspa.filum.app.CycleResult
import pl.wspa.filum.sync.InterruptReason
import pl.wspa.filum.sync.SyncOutcome

/** Co WorkManager ma zrobić po zakończonym cyklu. */
enum class WorkerDecision { SUCCESS, RETRY, FAILURE }

/**
 * Przełożenie wyniku cyklu na decyzję WorkManagera.
 *
 * Wydzielone z workera, żeby dało się to przetestować bez Androida. Nie jest to
 * kosmetyka: pomyłka tutaj albo każe systemowi dobijać się do wyłączonego serwera
 * co kilkanaście minut, albo — odwrotnie — cicho porzuci pracę, która wymagała
 * tylko ponowienia.
 */
object CycleOutcomes {

    fun decide(result: CycleResult): WorkerDecision = when (result) {
        is CycleResult.Done -> when (val outcome = result.outcome) {
            is SyncOutcome.Completed -> WorkerDecision.SUCCESS

            is SyncOutcome.Interrupted -> when (outcome.reason) {
                // Budżet wyczerpany. Ponawianie nic nie da, bo limit jest dobowy,
                // a kolejka i tak czeka na następne okno.
                InterruptReason.BUDGET -> WorkerDecision.SUCCESS

                // Sieć zniknęła w trakcie. Warto spróbować, gdy wróci.
                InterruptReason.CONNECTION -> WorkerDecision.RETRY
            }

            is SyncOutcome.Failed -> WorkerDecision.RETRY
        }

        // Brak sparowania to nie usterka przejściowa — potrzebna jest decyzja
        // użytkownika, a ponawianie tylko zużywałoby baterię.
        is CycleResult.NotPaired -> WorkerDecision.FAILURE

        // Serwer wyłączony albo poza zasięgiem. Klasyczny przypadek na ponowienie
        // z wycofaniem wykładniczym.
        is CycleResult.NoRoute -> WorkerDecision.RETRY
    }
}
