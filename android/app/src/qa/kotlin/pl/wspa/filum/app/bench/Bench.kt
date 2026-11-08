package pl.wspa.filum.app.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import pl.wspa.filum.app.AppContainer
import pl.wspa.filum.app.CycleResult
import pl.wspa.filum.app.SyncRunner
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.scan.Environment
import pl.wspa.filum.scan.FilesystemScanner
import pl.wspa.filum.scan.MediaStoreScanner
import pl.wspa.filum.sync.SyncOutcome
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Random

/**
 * The measurements for the thesis (architecture §7), taken on the phone.
 *
 * Synthetic data lives in this app's own storage, so it never mixes with the user's files and
 * goes away with the qa build. Reading the user's library - for scan times and hashing speed -
 * only ever reports counts and timings, never a name.
 */
class Bench(private val context: Context, private val container: AppContainer) {

    private val home = File(context.filesDir, "bench")
    private val sets = File(home, "sets")

    /** What a measured cycle scans. It holds one run at a time, so each run uploads under new paths. */
    private val live = File(home, "live")

    private val results = File(home, "results.jsonl")

    suspend fun run(command: String, arguments: Map<String, String>): JSONObject = when (command) {
        "generate" -> generate(arguments)
        "sync" -> sync(arguments)
        "hash" -> hash(arguments)
        "scan" -> scan()
        "status" -> JSONObject().put("battery", battery()).put("wifi", wifi()).put("sets", JSONArray(sets.list().orEmpty().sorted()))
        "clean" -> clean()
        else -> throw IllegalArgumentException("unknown command \"$command\"")
    }

    fun record(line: String) {
        home.mkdirs()
        results.appendText(line + "\n")
    }

    /**
     * A set of `count` files of `size` bytes each.
     *
     * The content is pseudo-random from a fixed seed - incompressible, so no layer can make a
     * transfer look cheaper than it is - and the modification time is fixed in the past: the
     * scanner skips files written moments ago, and the same set comes out the same every time.
     */
    private fun generate(arguments: Map<String, String>): JSONObject {
        val name = arguments.need("set")
        val count = arguments.need("count").toInt()
        val size = arguments.need("size").toLong()
        val seed = arguments["seed"]?.toLong() ?: 1L
        require(count > 0 && size > 0) { "a set needs at least one file of at least one byte" }

        val directory = File(sets, name)
        val spec = File(sets, "$name.spec")
        val wanted = "$count $size $seed"
        if (directory.isDirectory && spec.isFile && spec.readText() == wanted) {
            return JSONObject().put("skipped", "the set already exists")
        }

        directory.deleteRecursively()
        directory.mkdirs()
        val random = Random(seed)
        val buffer = ByteArray(minOf(size, CHUNK).toInt())
        val started = SystemClock.elapsedRealtime()

        for (index in 1..count) {
            val file = File(directory, "f-%06d.bin".format(index))
            file.outputStream().use { output ->
                var left = size
                while (left > 0) {
                    val length = minOf(left, buffer.size.toLong()).toInt()
                    random.nextBytes(buffer)
                    output.write(buffer, 0, length)
                    left -= length
                }
            }
            file.setLastModified(FIXED_MTIME_MILLIS)
        }
        spec.writeText(wanted)

        return JSONObject()
            .put("files", count)
            .put("bytes", count * size)
            .put("ms", SystemClock.elapsedRealtime() - started)
    }

    /**
     * One full cycle over a set through the real SyncRunner - route race, Room queue, HTTP
     * client and all. The set moves under a fresh run directory first, so the server sees
     * files it does not hold yet, and moves back afterwards.
     *
     * `prune=false` keeps the queue's finished entries. A run under an earlier run's name then
     * finds that run's files already in the backup, and a set generated anew in between - with
     * another size - comes back changed: how the warning about many changed files is tried on
     * the phone.
     */
    private suspend fun sync(arguments: Map<String, String>): JSONObject {
        val name = arguments.need("set")
        val run = arguments.need("run")
        val parallel = arguments["parallel"]?.toInt() ?: 1
        val prune = arguments["prune"]?.toBooleanStrict() ?: true

        val source = File(sets, name)
        require(source.isDirectory) { "no set \"$name\" - generate it first" }
        val runDirectory = File(live, run)
        // A run name used before would find the server holding everything and send nothing.
        require(!runDirectory.exists()) { "run \"$run\" is already in use" }
        runDirectory.mkdirs()
        val target = File(runDirectory, name)
        check(source.renameTo(target)) { "could not move set \"$name\" into place" }

        try {
            // Finished queue entries from earlier runs would make every batch query longer,
            // and a run would no longer measure the same work as the one before it.
            if (prune) container.database.files().pruneCompleted(System.currentTimeMillis() + 1)

            // Time to pull the cable out before the battery is read (energy per gigabyte).
            arguments["startDelay"]?.toLong()?.let { delay(it * 1000) }

            val before = battery()
            val started = SystemClock.elapsedRealtime()
            val cycle = container.syncRunner.runOnce(
                mode = ManifestRequest.MODE_FULL,
                override = SyncRunner.Override(root = live, parallelTransfers = parallel),
            )
            val elapsed = SystemClock.elapsedRealtime() - started

            return JSONObject()
                .put("ms", elapsed)
                .put("cycle", describe(cycle))
                .put("batteryBefore", before)
                .put("batteryAfter", battery())
                .put("wifi", wifi())
        } finally {
            target.renameTo(source)
            runDirectory.delete()
        }
    }

    /**
     * SHA-256 throughput - the number the choice between fast and thorough mode rests on
     * (ADR-016). "memory" hashes one buffer over and over, the processor alone; "set" reads a
     * generated set, most likely from the page cache; "library" reads the user's own files,
     * mostly cold, as thorough mode does.
     */
    private fun hash(arguments: Map<String, String>): JSONObject {
        val source = arguments["source"] ?: "memory"
        val limit = (arguments["mb"]?.toLong() ?: 1024L) * MB
        val buffer = ByteArray((arguments["bufferKb"]?.toInt() ?: 64) * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        var files = 0
        var unreadable = 0
        val started = SystemClock.elapsedRealtimeNanos()

        when (source) {
            "memory" -> {
                Random(1).nextBytes(buffer)
                while (bytes < limit) {
                    digest.update(buffer)
                    bytes += buffer.size
                }
            }

            "set", "library" -> {
                val root = if (source == "set") File(sets, arguments.need("set")) else File(Environment.externalStorageRootPath())
                for (file in FilesystemScanner(root).walk()) {
                    if (bytes >= limit) break
                    try {
                        File(file.absolutePath).inputStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                digest.update(buffer, 0, read)
                                bytes += read
                            }
                        }
                        files++
                    } catch (e: IOException) {
                        unreadable++
                    }
                }
            }

            else -> throw IllegalArgumentException("unknown source \"$source\"")
        }

        digest.digest()
        val seconds = (SystemClock.elapsedRealtimeNanos() - started) / 1e9

        return JSONObject()
            .put("source", source)
            .put("files", files)
            .put("unreadable", unreadable)
            .put("bytes", bytes)
            .put("seconds", seconds)
            .put("mbPerSecond", bytes / MB.toDouble() / seconds)
            .put("provider", digest.provider.name)
    }

    /**
     * How long finding the files takes (architecture §7, experiment 6): a full tree walk,
     * MediaStore's full listing, and MediaStore's "what changed since this generation" - the
     * question an incremental cycle asks.
     */
    private fun scan(): JSONObject {
        val media = MediaStoreScanner(context)

        var walkFiles = 0
        var walkBytes = 0L
        val walk = timed {
            for (file in FilesystemScanner(File(Environment.externalStorageRootPath())).walk()) {
                walkFiles++
                walkBytes += file.size
            }
        }

        var listed = 0
        val listing = timed { listed = media.all().size }

        val generation = media.currentGeneration()
        var changed = 0
        val delta = timed { changed = media.changedSince(generation)?.size ?: -1 }

        return JSONObject()
            .put("walk", JSONObject().put("files", walkFiles).put("bytes", walkBytes).put("seconds", walk))
            .put("mediaStoreAll", JSONObject().put("files", listed).put("seconds", listing))
            .put("mediaStoreDelta", JSONObject().put("files", changed).put("seconds", delta))
    }

    /** Removes the generated sets; the results stay. */
    private fun clean(): JSONObject {
        val removed = sets.deleteRecursively() and live.deleteRecursively()
        return JSONObject().put("removed", removed)
    }

    private fun describe(cycle: CycleResult): JSONObject = when (cycle) {
        is CycleResult.Done -> describe(cycle.outcome).put("endpoint", cycle.endpoint.toString())
        is CycleResult.NotPaired -> JSONObject().put("result", "not paired")
        is CycleResult.NoRoute -> JSONObject().put("result", "no route").put("failures", JSONArray(cycle.failures))
    }

    private fun describe(outcome: SyncOutcome): JSONObject = when (outcome) {
        is SyncOutcome.Completed -> JSONObject()
            .put("result", "completed")
            .put("filesQueued", outcome.filesQueued)
            .put("filesSent", outcome.filesSent)
            .put("filesFailed", outcome.filesFailed)
            .put("bytesSent", outcome.bytesSent)

        is SyncOutcome.Interrupted -> JSONObject()
            .put("result", "interrupted:${outcome.reason.name.lowercase()}")
            .put("filesQueued", outcome.filesQueued)
            .put("filesSent", outcome.filesSent)
            .put("filesFailed", outcome.filesFailed)
            .put("bytesSent", outcome.bytesSent)

        is SyncOutcome.Failed -> JSONObject().put("result", "failed").put("reason", outcome.reason)
    }

    /**
     * The battery's own counters. Energy per gigabyte (experiment 5) is only meaningful with the
     * phone unplugged, which is why "plugged" goes with every reading.
     */
    private fun battery(): JSONObject {
        val manager = context.getSystemService(BatteryManager::class.java)
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        return JSONObject()
            .put("chargeUah", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
            .put("energyNwh", manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER))
            .put("currentUa", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
            .put("levelPercent", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("voltageMv", status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1)
            .put("temperatureDeciC", status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            .put("plugged", (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }

    /** The link the measurement ran over: speed and band, never the network's name. */
    private fun wifi(): JSONObject {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        // With a VPN up the active network is the tunnel, which carries no Wi-Fi details.
        @Suppress("DEPRECATION")
        val info = capabilities?.transportInfo as? WifiInfo
            ?: context.getSystemService(WifiManager::class.java)?.connectionInfo

        return JSONObject()
            .put("linkMbps", info?.linkSpeed ?: -1)
            .put("txMbps", info?.txLinkSpeedMbps ?: -1)
            .put("rxMbps", info?.rxLinkSpeedMbps ?: -1)
            .put("frequencyMhz", info?.frequency ?: -1)
            .put("rssiDbm", info?.rssi ?: 0)
            .put("standard", info?.wifiStandard ?: -1)
            .put("vpn", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false)
    }

    private inline fun timed(block: () -> Unit): Double {
        val started = SystemClock.elapsedRealtimeNanos()
        block()
        return (SystemClock.elapsedRealtimeNanos() - started) / 1e9
    }

    private fun Map<String, String>.need(key: String): String =
        this[key] ?: throw IllegalArgumentException("missing argument \"$key\"")

    private companion object {
        const val MB = 1024L * 1024
        const val CHUNK = 1024L * 1024

        /** 2026-01-01 00:00 UTC: outside any quiet window, and the same for every set. */
        const val FIXED_MTIME_MILLIS = 1_767_225_600_000L
    }
}
