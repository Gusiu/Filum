package pl.wspa.filum.app.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.wspa.filum.app.CycleResult
import pl.wspa.filum.net.EndpointKind
import pl.wspa.filum.net.ServerEndpoint
import pl.wspa.filum.sync.InterruptReason
import pl.wspa.filum.sync.SyncOutcome

/**
 * The decision whether to retry background work.
 *
 * Each of these cases has a concrete price when confused with another: retrying where it
 * is not needed hammers at a switched-off server and eats the battery; not retrying where
 * it was needed quietly abandons the queue until the next day.
 */
class CycleOutcomesTest {

    private val endpoint = ServerEndpoint("192.168.1.10", 8765, EndpointKind.LAN)

    private fun done(outcome: SyncOutcome) = CycleResult.Done(outcome, endpoint)

    @Test
    fun `a completed cycle finishes the work`() {
        assertEquals(
            WorkerDecision.SUCCESS,
            CycleOutcomes.decide(done(SyncOutcome.Completed(filesSent = 12, bytesSent = 4096, filesQueued = 12, filesFailed = 0))),
        )
    }

    @Test
    fun `an exhausted budget is not an error`() {
        // The limit is daily. Retrying in fifteen minutes changes nothing, and the
        // queue waits for the next window anyway.
        assertEquals(
            WorkerDecision.SUCCESS,
            CycleOutcomes.decide(done(SyncOutcome.Interrupted(InterruptReason.BUDGET, 40, 900, filesQueued = 100, filesFailed = 0))),
        )
    }

    @Test
    fun `a dropped connection deserves a retry`() {
        assertEquals(
            WorkerDecision.RETRY,
            CycleOutcomes.decide(done(SyncOutcome.Interrupted(InterruptReason.CONNECTION, 3, 120, filesQueued = 10, filesFailed = 0))),
        )
    }

    @Test
    fun `a failed manifest exchange is retried`() {
        assertEquals(
            WorkerDecision.RETRY,
            CycleOutcomes.decide(done(SyncOutcome.Failed("the server refused the manifest"))),
        )
    }

    @Test
    fun `no route is retried`() {
        // The server is sometimes simply switched off. WorkManager's exponential
        // backoff keeps the subsequent attempts cheap.
        assertEquals(
            WorkerDecision.RETRY,
            CycleOutcomes.decide(CycleResult.NoRoute(listOf("192.168.1.10:8765: no answer"))),
        )
    }

    @Test
    fun `not being paired ends the work in failure`() {
        // The one case where retrying is pointless: what is needed is a decision from
        // the user, not another attempt.
        assertEquals(WorkerDecision.FAILURE, CycleOutcomes.decide(CycleResult.NotPaired()))
    }
}
