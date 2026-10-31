package pl.wspa.filum.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.wspa.filum.data.SyncSessionEntity
import pl.wspa.filum.sync.InterruptReason
import pl.wspa.filum.sync.SyncOutcome

/**
 * The session row a finished cycle leaves behind - the only record of what a night did once
 * the journal has rolled over.
 */
class SessionRecordsTest {

    private val started = SyncSessionEntity("session-1", "delta", startedAt = 1_000)

    @Test
    fun `a finished session records how many files were queued and how many failed`() {
        // Both columns read zero after every cycle, including one that sent 235 files (B28).
        val record = SessionRecords.finished(
            started,
            SyncOutcome.Completed(filesSent = 7, bytesSent = 4_096, filesQueued = 10, filesFailed = 3),
            usedDelta = true,
            endedAt = 2_000,
        )

        assertEquals(10, record.filesTotal)
        assertEquals(7, record.filesDone)
        assertEquals(3, record.filesFailed)
        assertEquals(4_096, record.bytesSent)
        assertEquals(2_000L, record.endedAt)
        assertEquals("completed", record.result)
    }

    @Test
    fun `an interrupted session keeps its counts`() {
        val record = SessionRecords.finished(
            started,
            SyncOutcome.Interrupted(InterruptReason.BUDGET, filesSent = 2, bytesSent = 100, filesQueued = 5, filesFailed = 1),
            usedDelta = true,
            endedAt = 2_000,
        )

        assertEquals(5, record.filesTotal)
        assertEquals(2, record.filesDone)
        assertEquals(1, record.filesFailed)
        assertEquals("interrupted:budget", record.result)
    }

    @Test
    fun `a cycle that fell back to a full scan says so`() {
        val record = SessionRecords.finished(
            started,
            SyncOutcome.Completed(filesSent = 0, bytesSent = 0, filesQueued = 0, filesFailed = 0),
            usedDelta = false,
            endedAt = 2_000,
        )

        assertEquals("completed (full scan)", record.result)
    }

    @Test
    fun `a cycle that could not agree the manifest records no files`() {
        val record = SessionRecords.finished(
            started,
            SyncOutcome.Failed("Server unreachable"),
            usedDelta = true,
            endedAt = 2_000,
        )

        assertEquals(0, record.filesTotal)
        assertEquals(0, record.filesDone)
        assertEquals("failed:Server unreachable", record.result)
    }
}
