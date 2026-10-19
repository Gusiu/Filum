package pl.wspa.filum.app.work

import androidx.work.WorkInfo

/** The part of a [WorkInfo] the home screen decision needs, as a plain value. */
data class WorkSnapshot(
    val state: WorkInfo.State,
    val runAttemptCount: Int,
    val nextScheduleTimeMillis: Long,
    /** Whether this is the work started by the button rather than the periodic one. */
    val manual: Boolean,
)

/** What the background sync is doing, as far as the home screen needs to know. */
sealed interface SyncActivity {

    data object Idle : SyncActivity

    /** A cycle is running, or one started by hand has been queued and is about to run. */
    data object Running : SyncActivity

    /**
     * The last attempt failed and WorkManager will try again by itself, at [atMillis] when
     * it says when. Nothing runs in the meantime, so the user may start a cycle by hand.
     */
    data class RetryScheduled(val atMillis: Long?) : SyncActivity

    companion object {

        fun of(work: List<WorkSnapshot>): SyncActivity {
            val pending = work.filterNot { it.state.isFinished }

            if (pending.any { it.state == WorkInfo.State.RUNNING }) return Running

            // Manual work has no constraints and is expedited, so a first attempt that is
            // still queued starts within moments. Periodic work spends most of its life
            // queued and is not "running" until it actually runs.
            if (pending.any { it.manual && it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0 }) {
                return Running
            }

            val retries = pending.filter { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
            if (retries.isEmpty()) return Idle

            // WorkManager reports Long.MAX_VALUE when it has no time to give.
            val next = retries.minOf { it.nextScheduleTimeMillis }
            return RetryScheduled(next.takeIf { it != Long.MAX_VALUE })
        }
    }
}
