package pl.wspa.filum.app.work

import pl.wspa.filum.app.CycleResult
import pl.wspa.filum.sync.InterruptReason
import pl.wspa.filum.sync.SyncOutcome

/** What WorkManager should do once a cycle ends. */
enum class WorkerDecision { SUCCESS, RETRY, FAILURE }

/**
 * Translating a cycle's outcome into a WorkManager decision.
 *
 * Extracted from the worker so it can be tested without Android. This is not cosmetic: a
 * mistake here either has the system hammering at a switched-off server every few minutes
 * or - the other way round - quietly abandons work that only needed a retry.
 */
object CycleOutcomes {

    fun decide(result: CycleResult): WorkerDecision = when (result) {
        is CycleResult.Done -> when (val outcome = result.outcome) {
            is SyncOutcome.Completed -> WorkerDecision.SUCCESS

            is SyncOutcome.Interrupted -> when (outcome.reason) {
                // The budget is spent. Retrying achieves nothing, because the limit is daily
                // and the queue waits for the next window anyway.
                InterruptReason.BUDGET -> WorkerDecision.SUCCESS

                // The network vanished mid-cycle. Worth trying again when it returns.
                InterruptReason.CONNECTION -> WorkerDecision.RETRY
            }

            is SyncOutcome.Failed -> WorkerDecision.RETRY
        }

        // Not being paired is not a transient fault - it needs a decision from the
        // user, and retrying would only drain the battery.
        is CycleResult.NotPaired -> WorkerDecision.FAILURE

        // The server is off or out of reach. The classic case for a retry with
        // exponential backoff.
        is CycleResult.NoRoute -> WorkerDecision.RETRY
    }
}
