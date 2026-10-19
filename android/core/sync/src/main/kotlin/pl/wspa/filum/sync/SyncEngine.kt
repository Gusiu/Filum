package pl.wspa.filum.sync

import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.net.BundleEntry
import pl.wspa.filum.protocol.FailureKind
import pl.wspa.filum.protocol.IntegrityPolicy
import pl.wspa.filum.protocol.ManifestEntry
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.scan.ScannedFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.min

/**
 * The engine of one sync cycle.
 *
 * The whole thing is built around one principle: **every step saves state**. Killing the
 * process at any moment costs at most one chunk of one file, because resumability works at
 * three levels - mid-file (an offset), mid-queue (the database survives a restart) and
 * mid-campaign (the first dump spread over several nights).
 *
 * That is what makes the six-hour foreground service limit stop being a problem: the engine
 * does what it can and ends by itself.
 */
class SyncEngine(
    private val transport: SyncTransport,
    private val queue: SyncQueue,
    private val journal: SyncJournal,
    private val config: SyncConfig = SyncConfig(),
) {

    private var filesSent = 0
    private var bytesSent = 0L

    suspend fun run(
        sessionId: String,
        mode: String,
        files: Sequence<ScannedFile>,
        deadline: Deadline,
    ): SyncOutcome {
        filesSent = 0
        bytesSent = 0

        val reconciled = reconcile(sessionId, mode, files, deadline)
        if (reconciled != null) return reconciled

        return upload(deadline)
    }

    // --- agreeing the manifest (protocol §5.2) ---

    /** Returns an outcome when we have to stop; `null` when uploading can begin. */
    private suspend fun reconcile(
        sessionId: String,
        mode: String,
        files: Sequence<ScannedFile>,
        deadline: Deadline,
    ): SyncOutcome? {
        val batches = files.chunked(config.manifestBatch).iterator()
        var index = 0

        if (!batches.hasNext()) {
            // An incremental scan that finds nothing is the ordinary night after the first
            // backup; worded like the full scan's message it read as a failed scan.
            val message = if (mode == ManifestRequest.MODE_DELTA) {
                "No files changed since the last scan."
            } else {
                "The scan found no files."
            }
            journal.record(JournalLevel.INFO, "manifest_empty", message)
            return null
        }

        while (batches.hasNext()) {
            val batch = batches.next()
            val isLast = !batches.hasNext()

            queue.enqueue(batch)

            val request = ManifestRequest(
                session = sessionId,
                mode = mode,
                batch = index,
                final = isLast,
                files = batch.map {
                    ManifestEntry(it.canonicalPath, it.size, it.mtimeSeconds, digestFor(it, deadline))
                },
            )

            when (val response = transport.manifest(request)) {
                is ApiResult.Ok -> applyManifest(batch, response.value)

                is ApiResult.Unreachable -> {
                    journal.record(JournalLevel.WARN, "manifest_unreachable", response.message)
                    return SyncOutcome.Failed("Server unreachable: ${response.message}")
                }

                is ApiResult.Failure -> {
                    journal.record(JournalLevel.ERROR, response.code, response.message)
                    return SyncOutcome.Failed("The server refused the manifest: ${response.message}")
                }
            }

            index++

            // Agreeing a hundred thousand files takes fifty requests. With the budget spent
            // we stop here - the queue is already partly written, so the next cycle picks
            // the work up from this point.
            if (deadline.reached()) {
                journal.record(
                    JournalLevel.INFO, "deadline_manifest",
                    "Budget exhausted while agreeing the manifest, batch $index.",
                )
                return SyncOutcome.Interrupted(InterruptReason.BUDGET, filesSent, bytesSent)
            }
        }

        return null
    }

    /**
     * The digest for the manifest - computed in thorough mode only.
     *
     * The deadline is checked here, not only between batches: a batch holds up to two thousand
     * entries, and with large files hashing one can take longer than the whole remaining
     * background window. Omitting a digest is safe - the server will treat the entry as it
     * would in fast mode.
     */
    private fun digestFor(file: ScannedFile, deadline: Deadline): String? {
        if (config.integrityPolicy != IntegrityPolicy.THOROUGH) return null
        if (deadline.reached()) return null

        return try {
            sha256(File(file.absolutePath))
        } catch (e: IOException) {
            // The file vanished or is unreadable. There is no point aborting the exchange -
            // the transfer will report the same thing with a more specific message.
            null
        }
    }

    private suspend fun applyManifest(
        batch: List<ScannedFile>,
        response: pl.wspa.filum.protocol.ManifestResponse,
    ) {
        val needed = response.needed.associate { it.path to it.offset }
        val rejected = response.rejected.associate { it.path to it.error }

        // Content changed with the metadata untouched. The file enters the queue anyway,
        // because the server put it in "needed" - but this is an event the user is to be
        // told about, not an ordinary update.
        for (path in response.divergent) {
            journal.record(
                JournalLevel.WARN, "integrity_divergent",
                "$path: the content differs from what the ledger recorded, despite matching size and modification time.",
            )
        }

        // The server reports how many files it holds, but not which. Anything in neither
        // "needed" nor "rejected" the server already has - and we do not send it.
        val alreadyThere = batch.map { it.canonicalPath }
            .filterNot { it in needed || it in rejected }

        if (alreadyThere.isNotEmpty()) queue.markAlreadyOnServer(alreadyThere)
        if (needed.isNotEmpty()) queue.markNeeded(needed)

        for ((path, error) in rejected) {
            queue.markRejected(path, error)
            journal.record(JournalLevel.WARN, "path_rejected", "$path: $error")
        }
    }

    // --- uploading ---

    private suspend fun upload(deadline: Deadline): SyncOutcome {
        while (true) {
            if (deadline.reached()) return stopOnBudget()

            val batch = queue.nextBatch(UPLOAD_BATCH)
            if (batch.isEmpty()) {
                return SyncOutcome.Completed(filesSent, bytesSent)
            }

            // Small files go in bundles, large ones on their own. Across a hundred thousand
            // files, most of them tiny, HTTP request overhead starts to outweigh the
            // data transfer itself (ADR-008).
            val (small, large) = batch.partition { it.size <= config.smallFileThreshold && it.bytesSent == 0L }

            for (group in group(small)) {
                if (deadline.reached()) return stopOnBudget()

                when (sendBundle(group)) {
                    SendResult.INTERRUPTED ->
                        return SyncOutcome.Interrupted(InterruptReason.CONNECTION, filesSent, bytesSent)

                    else -> Unit
                }
            }

            for (entry in large) {
                if (deadline.reached()) return stopOnBudget()

                when (send(entry, deadline)) {
                    SendResult.SENT -> filesSent++
                    SendResult.INTERRUPTED ->
                        return SyncOutcome.Interrupted(InterruptReason.CONNECTION, filesSent, bytesSent)

                    SendResult.SKIPPED, SendResult.FAILED -> Unit
                }
            }
        }
    }

    /**
     * Ending because of the budget. Always through this one path, because a night cycle with
     * no journal entry cannot be diagnosed - all the user sees is that some files did not
     * arrive by morning, with no way to find out why.
     */
    private suspend fun stopOnBudget(): SyncOutcome {
        journal.record(
            JournalLevel.INFO, "deadline_upload",
            "Budget exhausted; stopping cleanly after $filesSent files ($bytesSent B).",
        )
        return SyncOutcome.Interrupted(InterruptReason.BUDGET, filesSent, bytesSent)
    }

    private enum class SendResult { SENT, SKIPPED, FAILED, INTERRUPTED }

    /**
     * Splits files into bundles fitting the limits from the ping response.
     *
     * A file larger than the bundle limit cannot travel in one - it ends up alone in a bundle
     * and is rejected by the size threshold above anyway; all we watch here is that we never
     * build a bundle the server would refuse.
     */
    private fun group(files: List<QueuedFile>): List<List<QueuedFile>> {
        val groups = mutableListOf<List<QueuedFile>>()
        var current = mutableListOf<QueuedFile>()
        var bytes = 0L

        for (file in files) {
            val wouldExceed = bytes + file.size > config.bundleMaxBytes ||
                current.size >= config.bundleMaxCount

            if (wouldExceed && current.isNotEmpty()) {
                groups += current
                current = mutableListOf()
                bytes = 0
            }

            current += file
            bytes += file.size
        }

        if (current.isNotEmpty()) groups += current
        return groups
    }

    private suspend fun sendBundle(group: List<QueuedFile>): SendResult {
        val entries = mutableListOf<BundleEntry>()
        val byPath = mutableMapOf<String, QueuedFile>()

        for (queued in group) {
            val file = File(queued.absolutePath)

            if (!file.isFile) {
                queue.markPermanentFailure(queued.canonicalPath, "the file no longer exists")
                continue
            }
            if (file.length() != queued.size || Math.floorDiv(file.lastModified(), 1000L) != queued.mtime) {
                queue.markTransientFailure(queued.canonicalPath, "the file changed after the scan")
                continue
            }

            entries += BundleEntry(
                canonicalPath = queued.canonicalPath,
                file = file,
                size = queued.size,
                mtime = queued.mtime,
                digest = sha256(file),
            )
            byPath[queued.canonicalPath] = queued
        }

        if (entries.isEmpty()) return SendResult.SKIPPED

        return when (val result = transport.putBundle(entries)) {
            is ApiResult.Ok -> {
                applyBundleResults(result.value.results, byPath)
                SendResult.SENT
            }

            is ApiResult.Unreachable -> {
                // The bundle did not arrive whole. All of its files return to the queue - we
                // have no idea how many the server managed to store, and re-sending breaks
                // nothing, because the manifest will sift them out anyway.
                entries.forEach { queue.markTransientFailure(it.canonicalPath, result.message) }
                SendResult.INTERRUPTED
            }

            is ApiResult.Failure -> {
                // Broken framing or an exceeded limit invalidates the whole bundle.
                entries.forEach { queue.markTransientFailure(it.canonicalPath, result.code) }
                journal.record(JournalLevel.WARN, result.code, "Bundle rejected: ${result.message}")
                SendResult.FAILED
            }
        }
    }

    private suspend fun applyBundleResults(
        results: List<pl.wspa.filum.protocol.BundleFileResult>,
        byPath: Map<String, QueuedFile>,
    ) {
        val reported = results.map { it.path }.toSet()

        for (result in results) {
            val queued = byPath[result.path] ?: continue

            if (result.ok) {
                queue.markDone(result.path)
                filesSent++
                bytesSent += queued.size
                continue
            }

            // Rejecting one file inside a bundle carries the same weight as on a PUT:
            // a bad path is permanent, the rest is worth retrying.
            val error = result.error.orEmpty()
            if (error in PERMANENT_BUNDLE_ERRORS) {
                queue.markPermanentFailure(result.path, error)
                journal.record(JournalLevel.WARN, error, "${result.path}: rejected within a bundle")
            } else {
                queue.markTransientFailure(result.path, error)
            }
        }

        // The server said nothing about a file we sent. We do not assume success - it
        // returns to the queue and resolves itself at the next manifest.
        for ((path, _) in byPath) {
            if (path !in reported) queue.markTransientFailure(path, "no result in the response")
        }
    }

    private suspend fun send(entry: QueuedFile, deadline: Deadline): SendResult {
        val file = File(entry.absolutePath)

        if (!file.isFile) {
            // The user deleted the file between the scan and the upload. That is not an error -
            // the copy from earlier cycles stays on the server anyway (invariant I1).
            queue.markPermanentFailure(entry.canonicalPath, "the file no longer exists")
            return SendResult.SKIPPED
        }

        val currentMtime = Math.floorDiv(file.lastModified(), 1000L)
        if (file.length() != entry.size || currentMtime != entry.mtime) {
            // The file changed after the scan. Sending it now would splice two versions;
            // it returns to the queue on the next scan, with an up-to-date identity.
            queue.markTransientFailure(entry.canonicalPath, "the file changed after the scan")
            journal.record(
                JournalLevel.INFO, "file_changed",
                "${entry.canonicalPath} changed after the scan; it will return next cycle.",
            )
            return SendResult.SKIPPED
        }

        if (entry.attempts >= config.maxAttempts) {
            queue.markPermanentFailure(entry.canonicalPath, "attempt limit exceeded")
            journal.record(
                JournalLevel.ERROR, "too_many_attempts",
                "${entry.canonicalPath}: ${config.maxAttempts} failed attempts, setting it aside.",
            )
            return SendResult.FAILED
        }

        return transfer(entry, file, deadline)
    }

    private suspend fun transfer(entry: QueuedFile, file: File, deadline: Deadline): SendResult {
        var offset = entry.bytesSent
        var mismatches = 0

        // The digest is always computed, including on resume. Resuming saves transmission,
        // not reads from the phone's storage - and without a digest the server accepts
        // nothing anyway (ADR-016). The price is one local read; the gain is a ledger entry.
        val digest = sha256(file)

        while (offset < entry.size) {
            if (deadline.reached()) return SendResult.INTERRUPTED

            val length = min(config.chunkBytes, entry.size - offset)

            val result = transport.putFile(
                canonicalPath = entry.canonicalPath,
                file = file,
                size = entry.size,
                mtime = entry.mtime,
                offset = offset,
                length = length,
                // The digest goes with every chunk, although the server only checks it on
                // commit. Otherwise the ADR-016 requirement would reject the intermediate ones,
                // and a few dozen header bytes against a 32 MB body make no difference.
                digest = digest,
            )

            when (result) {
                is ApiResult.Ok -> {
                    bytesSent += result.value.received
                    offset = result.value.offset
                    queue.markProgress(entry.canonicalPath, offset)

                    if (result.value.complete) {
                        queue.markDone(entry.canonicalPath)
                        return SendResult.SENT
                    }
                }

                is ApiResult.Unreachable -> {
                    queue.markTransientFailure(entry.canonicalPath, result.message)
                    return SendResult.INTERRUPTED
                }

                is ApiResult.Failure -> {
                    val expected = result.expectedOffset
                    if (expected != null && expected != offset) {
                        // The server knows better what it holds. We adopt its state, but not
                        // indefinitely: circling would mean something on the other side is
                        // changing while we work.
                        mismatches++
                        if (mismatches > MAX_OFFSET_CORRECTIONS) {
                            queue.markTransientFailure(entry.canonicalPath, "unstable offset")
                            return SendResult.FAILED
                        }

                        journal.record(
                            JournalLevel.INFO, "offset_corrected",
                            "${entry.canonicalPath}: resuming from $expected instead of $offset.",
                        )
                        offset = expected
                        queue.markProgress(entry.canonicalPath, expected)
                        continue
                    }

                    return recordFailure(entry, result)
                }
            }
        }

        // The loop exited with no confirmation of completion - the server counted differently.
        queue.markTransientFailure(entry.canonicalPath, "the server did not confirm completion")
        return SendResult.FAILED
    }

    private suspend fun recordFailure(entry: QueuedFile, failure: ApiResult.Failure): SendResult =
        when (failure.kind) {
            FailureKind.PERMANENT -> {
                queue.markPermanentFailure(entry.canonicalPath, failure.code)
                journal.record(
                    JournalLevel.ERROR, failure.code,
                    "${entry.canonicalPath}: ${failure.message}",
                )
                SendResult.FAILED
            }

            FailureKind.TRANSIENT -> {
                queue.markTransientFailure(entry.canonicalPath, failure.code)
                journal.record(
                    JournalLevel.WARN, failure.code,
                    "${entry.canonicalPath}: ${failure.message}",
                )
                SendResult.FAILED
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** How many entries we take from the queue at once. Fewer means more database queries. */
        const val UPLOAD_BATCH = 256

        /** Bundle errors after which retrying is pointless - the fault is the file's, not the network's. */
        val PERMANENT_BUNDLE_ERRORS = setOf(
            "absolute", "traversal", "dot_segment", "empty_segment", "nul", "path_too_long",
        )

        /** How many times we let the server correct our idea of the offset. */
        const val MAX_OFFSET_CORRECTIONS = 3
    }
}
