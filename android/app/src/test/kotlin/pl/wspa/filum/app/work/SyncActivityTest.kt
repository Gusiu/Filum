package pl.wspa.filum.app.work

import androidx.work.WorkInfo.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the home screen shows about the background sync.
 *
 * The costly confusion is between a cycle that runs and one that waits out a retry delay:
 * shown as running, the waiting one keeps the button disabled for as long as the backoff
 * lasts, while nothing at all is happening.
 */
class SyncActivityTest {

    private fun manual(state: State, attempts: Int = 0, next: Long = Long.MAX_VALUE) =
        WorkSnapshot(state, attempts, next, manual = true)

    private fun periodic(state: State, attempts: Int = 0, next: Long = Long.MAX_VALUE) =
        WorkSnapshot(state, attempts, next, manual = false)

    @Test
    fun `nothing scheduled is idle`() {
        assertEquals(SyncActivity.Idle, SyncActivity.of(emptyList()))
    }

    @Test
    fun `a running cycle of either kind is running`() {
        assertEquals(SyncActivity.Running, SyncActivity.of(listOf(manual(State.RUNNING))))
        assertEquals(SyncActivity.Running, SyncActivity.of(listOf(periodic(State.RUNNING))))
    }

    @Test
    fun `a manual cycle queued for its first attempt counts as running`() {
        assertEquals(SyncActivity.Running, SyncActivity.of(listOf(manual(State.ENQUEUED))))
    }

    @Test
    fun `periodic work waiting for its next period is idle`() {
        assertEquals(SyncActivity.Idle, SyncActivity.of(listOf(periodic(State.ENQUEUED, next = 5_000))))
    }

    @Test
    fun `a manual cycle waiting out a retry delay is not running`() {
        assertEquals(
            SyncActivity.RetryScheduled(1_800_000),
            SyncActivity.of(listOf(manual(State.ENQUEUED, attempts = 2, next = 1_800_000))),
        )
    }

    @Test
    fun `the earliest of several retries is shown`() {
        val work = listOf(
            manual(State.ENQUEUED, attempts = 1, next = 900_000),
            periodic(State.ENQUEUED, attempts = 3, next = 600_000),
        )

        assertEquals(SyncActivity.RetryScheduled(600_000), SyncActivity.of(work))
    }

    @Test
    fun `an unknown retry time is reported as unknown`() {
        assertEquals(
            SyncActivity.RetryScheduled(null),
            SyncActivity.of(listOf(manual(State.ENQUEUED, attempts = 1))),
        )
    }

    @Test
    fun `finished work is ignored`() {
        val work = listOf(manual(State.FAILED, attempts = 5), manual(State.SUCCEEDED), periodic(State.CANCELLED))

        assertEquals(SyncActivity.Idle, SyncActivity.of(work))
    }
}
