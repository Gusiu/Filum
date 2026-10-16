package pl.wspa.filum.sync

import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.net.BundleEntry
import pl.wspa.filum.protocol.BundleFileResult
import pl.wspa.filum.protocol.BundleResponse
import pl.wspa.filum.protocol.ErrorResponse
import pl.wspa.filum.protocol.FileChunkResponse
import pl.wspa.filum.protocol.HttpStatus
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.protocol.ManifestResponse
import pl.wspa.filum.protocol.NeededFile
import pl.wspa.filum.protocol.RejectedFile
import pl.wspa.filum.scan.ScannedFile
import java.io.File
import java.io.IOException

/**
 * A fake queue: a complete in-memory implementation.
 *
 * It behaves like Room - writes persist within the test - so we check real state
 * transitions rather than whether the engine called the right method.
 */
class FakeQueue : SyncQueue {

    enum class State { PENDING, DONE, TRANSIENT, PERMANENT }

    data class Row(
        val file: ScannedFile,
        var state: State = State.PENDING,
        var bytesSent: Long = 0,
        var attempts: Int = 0,
        var lastError: String? = null,
    )

    val rows = linkedMapOf<String, Row>()

    override suspend fun enqueue(files: List<ScannedFile>) {
        for (file in files) {
            val existing = rows[file.canonicalPath]
            if (existing != null && existing.file.size == file.size && existing.file.mtime() == file.mtime()) {
                continue
            }
            rows[file.canonicalPath] = Row(file)
        }
    }

    private fun ScannedFile.mtime() = mtimeSeconds

    override suspend fun markAlreadyOnServer(paths: List<String>) {
        paths.forEach { rows[it]?.state = State.DONE }
    }

    override suspend fun markNeeded(offsets: Map<String, Long>) {
        for ((path, offset) in offsets) {
            rows[path]?.apply {
                state = State.PENDING
                bytesSent = offset
            }
        }
    }

    override suspend fun markRejected(path: String, error: String) {
        rows[path]?.apply {
            state = State.PERMANENT
            lastError = error
        }
    }

    override suspend fun markProgress(path: String, bytesSent: Long) {
        rows[path]?.bytesSent = bytesSent
    }

    override suspend fun markDone(path: String) {
        rows[path]?.apply {
            state = State.DONE
            lastError = null
        }
    }

    override suspend fun markTransientFailure(path: String, error: String) {
        rows[path]?.apply {
            state = State.TRANSIENT
            attempts++
            lastError = error
        }
    }

    override suspend fun markPermanentFailure(path: String, error: String) {
        rows[path]?.apply {
            state = State.PERMANENT
            attempts++
            lastError = error
        }
    }

    override suspend fun nextBatch(limit: Int): List<QueuedFile> =
        rows.values
            .filter { it.state == State.PENDING }
            .take(limit)
            .map {
                QueuedFile(
                    canonicalPath = it.file.canonicalPath,
                    absolutePath = it.file.absolutePath,
                    size = it.file.size,
                    mtime = it.file.mtimeSeconds,
                    bytesSent = it.bytesSent,
                    attempts = it.attempts,
                )
            }

    override suspend fun pendingCount(): Int = rows.values.count { it.state == State.PENDING }

    fun state(path: String): State? = rows[path]?.state
    fun error(path: String): String? = rows[path]?.lastError
}

class FakeJournal : SyncJournal {
    data class Entry(val level: JournalLevel, val code: String, val message: String)

    val entries = mutableListOf<Entry>()

    override suspend fun record(level: JournalLevel, code: String, message: String) {
        entries += Entry(level, code, message)
    }

    fun codes(): List<String> = entries.map { it.code }
    fun has(code: String): Boolean = entries.any { it.code == code }
}

/**
 * A fake server that keeps the bytes it receives. It reproduces the chunk semantics of
 * protocol §5.3, offset conflicts included, so the resume test exercises a real sequence
 * rather than a scripted set of answers.
 */
class FakeTransport : SyncTransport {

    val received = linkedMapOf<String, StringBuilder>()
    val requests = mutableListOf<ManifestRequest>()
    val digestsSeen = linkedMapOf<String, String?>()
    val putCalls = mutableListOf<Triple<String, Long, Long>>()

    /** The digest from every individual PUT request - not only the closing one. */
    val digestsPerCall = mutableListOf<String?>()

    var manifestResponder: (ManifestRequest) -> ApiResult<ManifestResponse> = { request ->
        ApiResult.Ok(
            ManifestResponse(
                have = 0,
                needed = request.files.map { NeededFile(it.path, 0) },
                rejected = emptyList(),
            )
        )
    }

    /** Replaced in tests to force a specific answer to the nth PUT request. */
    var putInterceptor: ((call: Int, path: String, offset: Long) -> ApiResult<FileChunkResponse>?) =
        { _, _, _ -> null }

    private var putCount = 0

    override suspend fun manifest(request: ManifestRequest): ApiResult<ManifestResponse> {
        requests += request
        for (entry in request.files) manifestDigests[entry.path] = entry.digest
        return manifestResponder(request)
    }

    /** The digests declared in the manifest - empty while the client is in fast mode. */
    val manifestDigests = linkedMapOf<String, String?>()

    override suspend fun putFile(
        canonicalPath: String,
        file: File,
        size: Long,
        mtime: Long,
        offset: Long,
        length: Long,
        digest: String?,
    ): ApiResult<FileChunkResponse> {
        putCount++
        putCalls += Triple(canonicalPath, offset, length)
        digestsPerCall += digest

        putInterceptor(putCount, canonicalPath, offset)?.let { return it }

        val buffer = received.getOrPut(canonicalPath) { StringBuilder() }

        if (offset != buffer.length.toLong()) {
            return ApiResult.failure(
                HttpStatus.CONFLICT,
                ErrorResponse("offset_mismatch", "mismatch", buffer.length.toLong()),
            )
        }

        val content = file.readText().substring(offset.toInt(), (offset + length).toInt())
        buffer.append(content)

        val complete = buffer.length.toLong() == size
        if (complete) digestsSeen[canonicalPath] = digest

        return ApiResult.Ok(FileChunkResponse(length, buffer.length.toLong(), complete))
    }

    val bundles = mutableListOf<List<String>>()

    /** Replaced in tests to force the server's answer to a bundle. */
    var bundleResponder: ((List<BundleEntry>) -> ApiResult<BundleResponse>)? = null

    override suspend fun putBundle(entries: List<BundleEntry>): ApiResult<BundleResponse> {
        bundles += entries.map { it.canonicalPath }

        bundleResponder?.let { return it(entries) }

        val results = entries.map { entry ->
            val actual = sha256(entry.file.readBytes())
            if (actual != entry.digest) {
                BundleFileResult(entry.canonicalPath, ok = false, error = "digest_mismatch")
            } else {
                received[entry.canonicalPath] = StringBuilder(entry.file.readText())
                BundleFileResult(entry.canonicalPath, ok = true)
            }
        }

        return ApiResult.Ok(BundleResponse(results))
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun content(path: String): String = received[path]?.toString().orEmpty()

    companion object {
        fun unreachable(): ApiResult<FileChunkResponse> =
            ApiResult.Unreachable(IOException("connection dropped"))

        fun rejected(): ApiResult<FileChunkResponse> = ApiResult.failure(
            HttpStatus.UNPROCESSABLE, ErrorResponse("traversal", "unacceptable path"),
        )

        fun outOfSpace(): ApiResult<FileChunkResponse> = ApiResult.failure(
            HttpStatus.INSUFFICIENT_STORAGE, ErrorResponse("insufficient_storage", "brak miejsca"),
        )

        fun manifestRejects(vararg paths: String): (ManifestRequest) -> ApiResult<ManifestResponse> = { request ->
            ApiResult.Ok(
                ManifestResponse(
                    have = 0,
                    needed = request.files.filterNot { it.path in paths }.map { NeededFile(it.path, 0) },
                    rejected = paths.map { RejectedFile(it, "traversal") },
                )
            )
        }
    }
}
