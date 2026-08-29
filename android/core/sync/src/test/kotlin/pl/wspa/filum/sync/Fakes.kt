package pl.wspa.filum.sync

import pl.wspa.filum.net.ApiResult
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
 * Atrapa kolejki: pełna implementacja w pamięci.
 *
 * Zachowuje się jak Room — zapisy są trwałe w obrębie testu — więc sprawdzamy
 * prawdziwe przejścia stanów, a nie to, czy silnik wywołał odpowiednią metodę.
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
 * Atrapa serwera przechowująca odebrane bajty. Odwzorowuje semantykę fragmentów
 * z §5.3 protokołu, łącznie z konfliktem przesunięcia — dzięki temu test wznawiania
 * sprawdza realny przebieg, a nie sekwencję zaprogramowanych odpowiedzi.
 */
class FakeTransport : SyncTransport {

    val received = linkedMapOf<String, StringBuilder>()
    val requests = mutableListOf<ManifestRequest>()
    val digestsSeen = linkedMapOf<String, String?>()
    val putCalls = mutableListOf<Triple<String, Long, Long>>()

    var manifestResponder: (ManifestRequest) -> ApiResult<ManifestResponse> = { request ->
        ApiResult.Ok(
            ManifestResponse(
                have = 0,
                needed = request.files.map { NeededFile(it.path, 0) },
                rejected = emptyList(),
            )
        )
    }

    /** Podmieniana w testach, żeby wymusić konkretną odpowiedź na n-te żądanie PUT. */
    var putInterceptor: ((call: Int, path: String, offset: Long) -> ApiResult<FileChunkResponse>?) =
        { _, _, _ -> null }

    private var putCount = 0

    override suspend fun manifest(request: ManifestRequest): ApiResult<ManifestResponse> {
        requests += request
        return manifestResponder(request)
    }

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

        putInterceptor(putCount, canonicalPath, offset)?.let { return it }

        val buffer = received.getOrPut(canonicalPath) { StringBuilder() }

        if (offset != buffer.length.toLong()) {
            return ApiResult.failure(
                HttpStatus.CONFLICT,
                ErrorResponse("offset_mismatch", "niezgodność", buffer.length.toLong()),
            )
        }

        val content = file.readText().substring(offset.toInt(), (offset + length).toInt())
        buffer.append(content)

        val complete = buffer.length.toLong() == size
        if (complete) digestsSeen[canonicalPath] = digest

        return ApiResult.Ok(FileChunkResponse(length, buffer.length.toLong(), complete))
    }

    fun content(path: String): String = received[path]?.toString().orEmpty()

    companion object {
        fun unreachable(): ApiResult<FileChunkResponse> =
            ApiResult.Unreachable(IOException("zerwane połączenie"))

        fun rejected(): ApiResult<FileChunkResponse> = ApiResult.failure(
            HttpStatus.UNPROCESSABLE, ErrorResponse("traversal", "ścieżka nie do przyjęcia"),
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
