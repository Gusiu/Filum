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
 * Rejestracja pracy okresowej i uruchamianie na żądanie.
 *
 * Cykl automatyczny ma **warunki**, cykl ręczny ich **nie ma**. To nie jest
 * niekonsekwencja: warunki chronią użytkownika przed niespodzianką, a naciśnięcie
 * przycisku jest właśnie świadomą decyzją. Rozmiar transferu przez sieć komórkową
 * pokazuje przy tym ekran główny.
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

        // UPDATE, nie REPLACE: zmiana harmonogramu nie może kasować odliczania
        // do najbliższego uruchomienia ani gubić pracy właśnie trwającej.
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

    /** Czy jakikolwiek cykl właśnie trwa — do wygaszenia przycisku w interfejsie. */
    fun observeRunning(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(MANUAL_WORK)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }

    private fun constraintsFor(settings: SettingsEntity): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(settings.requireCharging)
        // Transfer przy niskim poziomie baterii to najprostszy sposób, żeby
        // użytkownik znienawidził aplikację.
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Okno swobody dla systemu. Im szersze, tym lepiej dla baterii — Android może
     * dołączyć naszą pracę do okna, w którym i tak budzi urządzenie.
     */
    private fun flexOf(interval: Duration): Duration =
        if (interval.toDays() >= 7) Duration.ofHours(12) else Duration.ofHours(4)
}
