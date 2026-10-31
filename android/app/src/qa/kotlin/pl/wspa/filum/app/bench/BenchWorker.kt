package pl.wspa.filum.app.bench

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
import org.json.JSONObject
import pl.wspa.filum.app.FilumApplication

/**
 * Runs one measurement command and appends its result to `files/bench/results.jsonl`.
 *
 * It runs as a foreground service with a Wi-Fi lock, like a real backup cycle in SyncWorker:
 * without the lock the adapter's power saving cuts throughput, and a measurement taken
 * without it would describe a cycle that never happens.
 */
class BenchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo())

        val arguments = inputData.keyValueMap.mapValues { it.value.toString() }
        val command = arguments["command"].orEmpty()
        val bench = Bench(applicationContext, (applicationContext as FilumApplication).container)

        val lock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }

        val result = try {
            bench.run(command, arguments)
        } catch (e: Exception) {
            // A failed measurement is recorded too, so the computer waiting for this line
            // learns why instead of waiting for ever.
            JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            lock?.release()
        }

        val line = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("command", command)
            .put("arguments", JSONObject(arguments))
            .put("result", result)
            .toString()

        bench.record(line)
        Log.i(TAG, line)
        return Result.success()
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Measurements", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Filum QA")
            .setContentText("Measuring")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        const val TAG = "FilumBench"
        const val CHANNEL_ID = "filum-bench"
        const val NOTIFICATION_ID = 7
        const val WIFI_LOCK_TAG = "filum:bench"
    }
}
