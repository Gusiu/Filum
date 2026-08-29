package pl.wspa.filum.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import pl.wspa.filum.app.CycleResult
import pl.wspa.filum.app.FilumApplication
import pl.wspa.filum.data.BudgetTracker
import pl.wspa.filum.data.LogLevel
import pl.wspa.filum.data.LogEventEntity
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.sync.Deadline

/**
 * Cykl synchronizacji wykonywany w tle, jako usługa pierwszoplanowa typu `dataSync`.
 *
 * Praca jest z założenia przerywalna. Nie próbujemy dokończyć wszystkiego za jednym
 * razem — pierwsza synchronizacja to około 200 GB, więc i tak rozkłada się na kilka
 * nocy. Worker robi tyle, ile pozwala budżet, zapisuje stan i kończy się sam.
 */
class SyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    private val container get() = (applicationContext as FilumApplication).container

    override suspend fun doWork(): Result {
        val budget = BudgetTracker(container.database.budget())
        budget.prune()

        if (!budget.hasRoomToStart()) {
            // Budżet wyczerpany w oknie kroczącym. To nie jest błąd — po prostu
            // nie ta doba. Zwracamy powodzenie, żeby WorkManager nie mnożył prób.
            log(LogLevel.INFO, "budget_exhausted", "Budżet pracy w tle wyczerpany; wracam później.")
            return Result.success()
        }

        val remaining = budget.remainingSeconds()
        val startedAt = System.currentTimeMillis()
        val deadlineAt = startedAt + remaining * 1000

        setForeground(foregroundInfo("Szukam serwera…"))

        // Bez blokady oszczędzanie energii karty Wi-Fi potrafi wielokrotnie ściąć
        // przepustowość, a budżet leci wtedy na czekanie zamiast na transfer.
        val wifiLock = acquireWifiLock()

        return try {
            val result = container.syncRunner.runOnce(
                mode = if (isFullReconciliation()) ManifestRequest.MODE_FULL else ManifestRequest.MODE_DELTA,
                deadline = Deadline { System.currentTimeMillis() >= deadlineAt },
            )
            interpret(result)
        } catch (e: Exception) {
            Log.w(TAG, "Cykl zakończony wyjątkiem", e)
            log(LogLevel.ERROR, "worker_failed", e.message ?: e::class.java.simpleName)
            Result.retry()
        } finally {
            runCatching { wifiLock?.release() }
            // Zużyty czas zapisujemy zawsze, także po wyjątku i po anulowaniu —
            // inaczej budżet przestałby odzwierciedlać rzeczywistość i system
            // ubiłby pracę w losowym momencie następnego cyklu.
            budget.record(startedAt, System.currentTimeMillis())
        }
    }

    private fun interpret(result: CycleResult): Result = when (CycleOutcomes.decide(result)) {
        WorkerDecision.SUCCESS -> Result.success()
        WorkerDecision.RETRY -> Result.retry()
        WorkerDecision.FAILURE -> Result.failure()
    }

    /**
     * Czy ten cykl ma uzgodnić komplet plików.
     *
     * Skan przyrostowy jest tani, ale opiera się na liczniku MediaStore, który nie
     * obejmuje plików spoza indeksu. Okresowa pełna rekoncyliacja wyłapuje to, co
     * przyrost przeoczył — i tylko ona pozwala serwerowi policzyć sieroty (§5.6).
     */
    private suspend fun isFullReconciliation(): Boolean {
        val last = container.database.scanState().get(VOLUME)?.lastFullScanAt ?: return true
        return System.currentTimeMillis() - last > FULL_SCAN_INTERVAL_MILLIS
    }

    private fun acquireWifiLock(): WifiManager.WifiLock? = runCatching {
        val manager = applicationContext.getSystemService(WifiManager::class.java)
        manager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private suspend fun log(level: LogLevel, code: String, message: String) {
        container.database.log().insert(
            LogEventEntity(at = System.currentTimeMillis(), level = level, code = code, message = message)
        )
        container.database.log().trim()
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        ensureChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Filum")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Synchronizacja", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Postęp kopii zapasowej w tle"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "FilumWorker"
        private const val CHANNEL_ID = "filum-sync"
        private const val NOTIFICATION_ID = 1
        private const val WIFI_LOCK_TAG = "filum:sync"
        private const val VOLUME = "external_primary"

        /** Co ile wykonujemy pełne uzgodnienie zamiast przyrostu. */
        private const val FULL_SCAN_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
