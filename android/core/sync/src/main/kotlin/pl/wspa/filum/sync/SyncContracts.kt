package pl.wspa.filum.sync

import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.net.BundleEntry
import pl.wspa.filum.net.FilumHttpClients
import pl.wspa.filum.protocol.BundleResponse
import pl.wspa.filum.protocol.FileChunkResponse
import pl.wspa.filum.protocol.IntegrityPolicy
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.protocol.ManifestResponse
import pl.wspa.filum.scan.ScannedFile
import java.io.File

/**
 * The sync engine's contracts.
 *
 * The engine knows neither OkHttp nor Room. This is not abstraction for its own sake:
 * the decisions taken here - when to stop, what to retry, what to call permanently failed -
 * are the hardest part of the whole client and have to be testable without a network,
 * a database or an emulator.
 */

/** The conversation with the server, already bound to a specific route. */
interface SyncTransport {

    suspend fun manifest(request: ManifestRequest): ApiResult<ManifestResponse>

    suspend fun putFile(
        canonicalPath: String,
        file: File,
        size: Long,
        mtime: Long,
        offset: Long,
        length: Long,
        digest: String?,
    ): ApiResult<FileChunkResponse>

    /** Sends many small files in one request (§5.4). */
    suspend fun putBundle(entries: List<BundleEntry>): ApiResult<BundleResponse>
}

/** A queue entry as the engine sees it. */
data class QueuedFile(
    val canonicalPath: String,
    val absolutePath: String,
    val size: Long,
    val mtime: Long,
    /** How many bytes the server has confirmed - the resume point. */
    val bytesSent: Long,
    val attempts: Int,
)

/** The queue's durable state. It has to survive the process being killed - that is what resumability means. */
interface SyncQueue {

    /**
     * Returns how many of [files] were already in the backup and now have another size or
     * modification time - files whose content changed after it was backed up.
     */
    suspend fun enqueue(files: List<ScannedFile>): Int

    /** The server already has these; nothing is sent. */
    suspend fun markAlreadyOnServer(paths: List<String>)

    /** The server gave a resume point for these paths. */
    suspend fun markNeeded(offsets: Map<String, Long>)

    suspend fun markRejected(path: String, error: String)

    suspend fun markProgress(path: String, bytesSent: Long)

    suspend fun markDone(path: String)

    suspend fun markTransientFailure(path: String, error: String)

    suspend fun markPermanentFailure(path: String, error: String)

    /**
     * Files waiting to be sent, including those that failed for now - least recently updated
     * first. The engine relies on that order: a file that failed during this cycle comes
     * after every file not yet tried.
     */
    suspend fun nextBatch(limit: Int): List<QueuedFile>

    suspend fun pendingCount(): Int
}

enum class JournalLevel { INFO, WARN, ERROR }

/** The journal shown in the application. Without it a night cycle cannot be diagnosed. */
interface SyncJournal {
    suspend fun record(level: JournalLevel, code: String, message: String)
}

/**
 * Whether there is still permission to work.
 *
 * One call instead of passing the budget, the network and the charging state separately -
 * the engine need not know **why** it should stop, only that it should stop cleanly.
 */
fun interface Deadline {
    fun reached(): Boolean
}

data class SyncConfig(
    /** The limit from the ping response; the client assumes none of its own (protocol §5.1). */
    val manifestBatch: Int = 2000,

    /**
     * The largest chunk in a single PUT request.
     *
     * Large files are split so that a single request does not take hours on a slow link, and
     * so the budget can be checked in the middle of a multi-gigabyte recording.
     */
    val chunkBytes: Long = 32L * 1024 * 1024,

    /**
     * How we detect that a file has changed (ADR-016).
     *
     * It does not govern sending a digest during a transfer - that is always computed and the
     * server requires it. What it governs is whether we **ask at all** whether a file taken to
     * be unchanged really is the same file.
     */
    val integrityPolicy: IntegrityPolicy = IntegrityPolicy.FAST,

    /** After this many failed attempts a file is treated as permanently problematic. */
    val maxAttempts: Int = 5,

    /**
     * Up to this size a file goes in a bundle rather than its own request (ADR-008).
     *
     * The value comes from the server's ping response; the client assumes none of its own.
     */
    val smallFileThreshold: Long = 1_048_576,

    val bundleMaxBytes: Long = 8L * 1024 * 1024,

    val bundleMaxCount: Int = 256,

    /**
     * How many transfers run at once; a bundle and a file sent on its own count as one each.
     *
     * Like the bundling threshold, this is to be settled by measurement (architecture §7,
     * experiment 2). With one, transfers go strictly one after another, in the order of the
     * batch.
     */
    val parallelTransfers: Int = 1,

    /**
     * How many files already in the backup have to change in one cycle for the journal and the
     * home screen to warn of it - what encryption by ransomware looks like from the phone.
     *
     * Routine cycles change few such files: the photos of a day are new files, and the one
     * incremental cycle measured on the test phone sent 21 files, new and changed together.
     * Fifty stays clear of that, while encrypting even one folder of photos goes past it.
     */
    val massChangeFiles: Int = 50,
) {
    init {
        require(parallelTransfers in 1..MAX_PARALLEL_TRANSFERS) {
            "parallelTransfers must be between 1 and $MAX_PARALLEL_TRANSFERS, was $parallelTransfers"
        }
    }

    companion object {
        /** The HTTP client lets this many calls to one host run at once; more would only queue. */
        const val MAX_PARALLEL_TRANSFERS = FilumHttpClients.MAX_REQUESTS_PER_HOST
    }
}

/**
 * Why the cycle ended early.
 *
 * A type, not a string: this decides whether the work should be retried or deferred to the
 * next window. Deciding it by comparing text would fall over silently the first time a
 * message is reworded.
 */
enum class InterruptReason {
    /** The background-work budget is spent. The queue waits; retrying now achieves nothing. */
    BUDGET,

    /** A dropped connection. Worth another try once the network returns. */
    CONNECTION,
}

sealed interface SyncOutcome {

    /**
     * Everything this cycle could send has gone. Files that failed for now wait in the queue
     * for the next cycle.
     *
     * The counts have no defaults on purpose: the session record is built from them, and a
     * count nobody sets reads as zero.
     */
    data class Completed(
        val filesSent: Int,
        val bytesSent: Long,
        /** Files waiting in the queue when uploading began. */
        val filesQueued: Int,
        /** Files the cycle tried to send and could not - set aside, or left for another try. */
        val filesFailed: Int,
    ) : SyncOutcome

    /**
     * We stopped by ourselves, at a moment of our choosing. The counts mean what they do in
     * [Completed]; [filesQueued] is zero when the cycle stopped before uploading began.
     */
    data class Interrupted(
        val reason: InterruptReason,
        val filesSent: Int,
        val bytesSent: Long,
        val filesQueued: Int,
        val filesFailed: Int,
    ) : SyncOutcome

    /** The manifest could not even be agreed. */
    data class Failed(val reason: String) : SyncOutcome
}
