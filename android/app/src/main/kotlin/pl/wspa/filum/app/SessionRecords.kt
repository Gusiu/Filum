package pl.wspa.filum.app

import pl.wspa.filum.data.SyncSessionEntity
import pl.wspa.filum.sync.SyncOutcome

/**
 * What a finished cycle writes into its session row.
 *
 * Extracted from SyncRunner so it can be tested without Room. The session table is the
 * record of what each night did, and a column nobody fills in reads as zero rather than
 * as missing (B28).
 */
object SessionRecords {

    fun finished(
        session: SyncSessionEntity,
        outcome: SyncOutcome,
        usedDelta: Boolean,
        endedAt: Long,
    ): SyncSessionEntity {
        val counted = when (outcome) {
            is SyncOutcome.Completed -> session.copy(
                filesTotal = outcome.filesQueued,
                filesDone = outcome.filesSent,
                filesFailed = outcome.filesFailed,
                bytesSent = outcome.bytesSent,
                result = "completed",
            )
            is SyncOutcome.Interrupted -> session.copy(
                filesTotal = outcome.filesQueued,
                filesDone = outcome.filesSent,
                filesFailed = outcome.filesFailed,
                bytesSent = outcome.bytesSent,
                result = "interrupted:${outcome.reason.name.lowercase()}",
            )
            is SyncOutcome.Failed -> session.copy(result = "failed:${outcome.reason}")
        }

        return counted.copy(
            endedAt = endedAt,
            result = if (usedDelta) counted.result else "${counted.result} (full scan)",
        )
    }
}
