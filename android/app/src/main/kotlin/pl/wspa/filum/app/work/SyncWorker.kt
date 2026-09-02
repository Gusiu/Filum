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
 * The sync cycle run in the background, as a `dataSync` foreground service.
 *
 * The work is interruptible by design. We do not try to finish everything in one go:
 * the first sync covers the device's whole library and on a typical phone does not fit
 * into a single background window, whatever that library weighs. The worker does what the
 * budget allows, saves state and ends by itself.
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
            // The budget is spent for the rolling window. That is not an error — just not
            // today. We return success so WorkManager does not multiply attempts.
            log(LogLevel.INFO, "budget_exhausted", "Background work budget spent; coming back later.")
            return Result.success()
        }

        val remaining = budget.remainingSeconds()
        val startedAt = System.currentTimeMillis()
        val deadlineAt = startedAt + remaining * 1000

        setForeground(foregroundInfo("Szukam serwera…"))

        // Without the lock, Wi-Fi power saving can cut throughput several times over,
        // and the budget then drains on waiting rather than on transferring.
        val wifiLock = acquireWifiLock()

        return try {
            val result = container.syncRunner.runOnce(
                mode = if (isFullReconciliation()) ManifestRequest.MODE_FULL else ManifestRequest.MODE_DELTA,
                deadline = Deadline { System.currentTimeMillis() >= deadlineAt },
            )
            interpret(result)
        } catch (e: Exception) {
            Log.w(TAG, "The cycle ended in an exception", e)
            log(LogLevel.ERROR, "worker_failed", e.message ?: e::class.java.simpleName)
            Result.retry()
        } finally {
            runCatching { wifiLock?.release() }
            // Time spent is always recorded, including after an exception and after
            // cancellation — otherwise the budget would stop reflecting reality and the
            // system would kill the work at a random point in the next cycle.
            budget.record(startedAt, System.currentTimeMillis())
        }
    }

    private fun interpret(result: CycleResult): Result = when (CycleOutcomes.decide(result)) {
        WorkerDecision.SUCCESS -> Result.success()
        WorkerDecision.RETRY -> Result.retry()
        WorkerDecision.FAILURE -> Result.failure()
    }

    /**
     * Whether this cycle should agree the complete file set.
     *
     * An incremental scan is cheap but rests on the MediaStore counter, which does not
     * cover files outside the index. A periodic full reconciliation catches what the
     * increment missed — and it is the only thing that lets the server compute orphans (§5.6).
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
            NotificationChannel(CHANNEL_ID, "Sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of the background backup"
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

        /** How often we do a full reconciliation instead of an increment. */
        private const val FULL_SCAN_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
