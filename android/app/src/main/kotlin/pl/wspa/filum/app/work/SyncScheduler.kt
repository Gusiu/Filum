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
import kotlinx.coroutines.flow.map
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

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(MANUAL_WORK)
        }
    }

    /** Whether any cycle is running — used to grey out the button in the interface. */
    fun observeRunning(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(MANUAL_WORK)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }

    private fun constraintsFor(settings: SettingsEntity): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(settings.requireCharging)
        // Transferring on a low battery is the simplest way to make a user hate the
        // application.
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Flexibility for the system. The wider the better for the battery — Android can attach
     * our work to a window in which it wakes the device anyway.
     */
    private fun flexOf(interval: Duration): Duration =
        if (interval.toDays() >= 7) Duration.ofHours(12) else Duration.ofHours(4)
}
