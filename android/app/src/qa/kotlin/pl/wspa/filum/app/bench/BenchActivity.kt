package pl.wspa.filum.app.bench

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Takes a measurement command from the computer:
 *
 *     adb shell am start -n pl.wspa.filum.qa/pl.wspa.filum.app.bench.BenchActivity \
 *         --es command sync --es set s1m --es run r001 --es parallel 3
 *
 * Every argument is a string extra. An activity rather than a broadcast receiver: a
 * measurement has to run as a foreground service, as a real backup cycle does, and Android
 * lets an app start one only while it is in the foreground itself. The screen stays up, over
 * the lock screen too, until the measurement ends; commands queue up and run one at a time.
 */
class BenchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val label = TextView(this).apply {
            textSize = 20f
            setPadding(48, 160, 48, 48)
        }
        setContentView(label)

        // Recreated after a rotation, the activity must not queue the same command again.
        if (savedInstanceState != null) return

        val extras = intent.extras
        if (extras == null) {
            finish()
            return
        }

        val input = Data.Builder()
        for (key in extras.keySet()) {
            extras.getString(key)?.let { input.putString(key, it) }
        }
        val request = OneTimeWorkRequestBuilder<BenchWorker>()
            .setInputData(input.build())
            .build()

        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        label.text = "Measuring: ${extras.getString("command")}"

        workManager.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info?.state?.isFinished == true) finish()
        }
    }

    private companion object {
        const val UNIQUE_WORK = "filum-bench"
    }
}
