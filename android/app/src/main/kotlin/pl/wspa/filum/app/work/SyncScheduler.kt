package pl.wspa.filum.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import pl.wspa.filum.data.Schedule
import pl.wspa.filum.data.SettingsEntity
import java.time.Duration

/**
 * Registering periodic work and starting it on demand.
 *
 * An automatic cycle has **conditions**, a manual one **does not**. That is not an
 * inconsistency: the conditions protect the user from a surprise, and pressing the button
 * is precisely a deliberate decision. The size of a transfer over cellular is shown by the
 * home screen at the time.
 */
object SyncScheduler {

    const val PERIODIC_WORK = "filum-periodic-sync"
    const val MANUAL_WORK = "filum-manual-sync"

    fun apply(context: Context, settings: SettingsEntity) {
        val manager = WorkManager.getInstance(context)

        val interval = when (settings.schedule) {
            Schedule.MANUAL -> {
                manager.cancelUniqueWork(PERIODIC_WORK)
                return
            }

            Schedule.DAILY -> Duration.ofDays(1)
            Schedule.WEEKLY -> Duration.ofDays(7)
        }

        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval, flexOf(interval))
            .setConstraints(constraintsFor(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(15))
            .build()

        // UPDATE, not REPLACE: changing the schedule must not reset the countdown to the
        // next run, nor lose work that is currently in progress.
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Starts a cycle now unless one is already running.
     *
     * Work that waits out a retry delay is replaced rather than kept: WorkManager refuses to
     * run it before its backoff ends, and after a few failures that is hours away. Pressing
     * the button is a deliberate decision, and it has to start something.
     */
    suspend fun runNow(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (activityOf(manager).first() == SyncActivity.Running) return

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        manager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(MANUAL_WORK)
        }
    }

    /** What the background sync is doing - for the button and the status line on the home screen. */
    fun observeActivity(context: Context): Flow<SyncActivity> =
        activityOf(WorkManager.getInstance(context))

    private fun activityOf(manager: WorkManager): Flow<SyncActivity> =
        combine(
            manager.getWorkInfosForUniqueWorkFlow(MANUAL_WORK),
            manager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK),
        ) { manual, periodic ->
            SyncActivity.of(manual.map { it.snapshot(manual = true) } + periodic.map { it.snapshot(manual = false) })
        }

    private fun WorkInfo.snapshot(manual: Boolean) =
        WorkSnapshot(state, runAttemptCount, nextScheduleTimeMillis, manual)

    private fun constraintsFor(settings: SettingsEntity): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(settings.requireCharging)
        // Transferring on a low battery is the simplest way to make a user hate the
        // application.
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Flexibility for the system. The wider the better for the battery - Android can attach
     * our work to a window in which it wakes the device anyway.
     */
    private fun flexOf(interval: Duration): Duration =
        if (interval.toDays() >= 7) Duration.ofHours(12) else Duration.ofHours(4)
}
