package pl.wspa.filum.app.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.wspa.filum.app.CycleResult
import pl.wspa.filum.net.EndpointKind
import pl.wspa.filum.net.ServerEndpoint
import pl.wspa.filum.sync.InterruptReason
import pl.wspa.filum.sync.SyncOutcome

/**
 * Decyzja o ponowieniu pracy w tle.
 *
 * Każdy z tych przypadków ma konkretną cenę, gdy zostanie pomylony: ponawianie
 * tam, gdzie nie trzeba, dobija się do wyłączonego serwera i zjada baterię;
 * brak ponowienia tam, gdzie było potrzebne, cicho porzuca kolejkę do następnej doby.
 */
class CycleOutcomesTest {

    private val endpoint = ServerEndpoint("192.168.1.10", 8765, EndpointKind.LAN)

    private fun done(outcome: SyncOutcome) = CycleResult.Done(outcome, endpoint)

    @Test
    fun `a completed cycle finishes the work`() {
        assertEquals(
            WorkerDecision.SUCCESS,
            CycleOutcomes.decide(done(SyncOutcome.Completed(filesSent = 12, bytesSent = 4096))),
        )
    }

    @Test
    fun `an exhausted budget is not an error`() {
        // Limit jest dobowy. Ponawianie za piętnaście minut nic nie zmieni,
        // a kolejka i tak czeka na następne okno.
        assertEquals(
            WorkerDecision.SUCCESS,
            CycleOutcomes.decide(done(SyncOutcome.Interrupted(InterruptReason.BUDGET, 40, 900))),
        )
    }

    @Test
    fun `a dropped connection deserves a retry`() {
        assertEquals(
            WorkerDecision.RETRY,
            CycleOutcomes.decide(done(SyncOutcome.Interrupted(InterruptReason.CONNECTION, 3, 120))),
        )
    }

    @Test
    fun `a failed manifest exchange is retried`() {
        assertEquals(
            WorkerDecision.RETRY,
            CycleOutcomes.decide(done(SyncOutcome.Failed("serwer odrzucił manifest"))),
        )
    }

    @Test
    fun `no route is retried`() {
        // Serwer bywa po prostu wyłączony. Wycofanie wykładnicze WorkManagera
        // sprawia, że kolejne próby nie są kosztowne.
        assertEquals(
            WorkerDecision.RETRY,
            CycleOutcomes.decide(CycleResult.NoRoute(listOf("192.168.1.10:8765: brak odpowiedzi"))),
        )
    }

    @Test
    fun `not being paired ends the work in failure`() {
        // To jedyny przypadek, w którym ponawianie jest bezcelowe: potrzebna jest
        // decyzja użytkownika, a nie kolejna próba.
        assertEquals(WorkerDecision.FAILURE, CycleOutcomes.decide(CycleResult.NotPaired()))
    }
}
