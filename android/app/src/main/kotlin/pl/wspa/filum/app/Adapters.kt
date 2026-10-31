package pl.wspa.filum.app

import pl.wspa.filum.data.FileEntryEntity
import pl.wspa.filum.data.FileEntryDao
import pl.wspa.filum.data.FileState
import pl.wspa.filum.data.LogEventDao
import pl.wspa.filum.data.LogEventEntity
import pl.wspa.filum.data.LogLevel
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.net.BundleEntry
import pl.wspa.filum.net.FilumApiClient
import pl.wspa.filum.net.ServerEndpoint
import pl.wspa.filum.protocol.BundleResponse
import pl.wspa.filum.protocol.FileChunkResponse
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.protocol.ManifestResponse
import pl.wspa.filum.scan.ScannedFile
import pl.wspa.filum.sync.JournalLevel
import pl.wspa.filum.sync.QueuedFile
import pl.wspa.filum.sync.SyncJournal
import pl.wspa.filum.sync.SyncQueue
import pl.wspa.filum.sync.SyncTransport
import java.io.File

/**
 * The adapters joining the engine to concrete technologies.
 *
 * The engine works against its own interfaces (`:core:sync`), and here we plug Room and
 * OkHttp into them. This is the only place that knows both worlds at once, which is why
 * replacing either side never touches the decision logic.
 */

/**
 * The queue, backed by Room.
 *
 * The canonical path is the primary key, so adding the same file again
 * in a new version overwrites the entry: that is the same file after a change, not a second one.
 */
class RoomSyncQueue(
    private val dao: FileEntryDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncQueue {

    /** The absolute path is not in the database: it follows from the scan and changes with the volume. */
    private val absolutePaths = HashMap<String, String>()

    override suspend fun enqueue(files: List<ScannedFile>) {
        val now = clock()

        val entries = files.map { file ->
            absolutePaths[file.canonicalPath] = file.absolutePath

            val existing = dao.get(file.canonicalPath)
            val unchanged = existing != null &&
                existing.size == file.size &&
                existing.mtime == file.mtimeSeconds

            if (unchanged) {
                existing!!
            } else {
                // A change of size or mtime invalidates the progress so far:
                // resuming would splice two different versions of the file.
                FileEntryEntity(
                    path = file.canonicalPath,
                    size = file.size,
                    mtime = file.mtimeSeconds,
                    state = FileState.PENDING,
                    bytesSent = 0,
                    updatedAt = now,
                )
            }
        }

        dao.upsert(entries)
    }

    override suspend fun markAlreadyOnServer(paths: List<String>) {
        val now = clock()
        paths.forEach { dao.updateProgress(it, FileState.DONE, 0, now) }
    }

    override suspend fun markNeeded(offsets: Map<String, Long>) {
        val now = clock()
        offsets.forEach { (path, offset) -> dao.updateProgress(path, FileState.PENDING, offset, now) }
    }

    override suspend fun markRejected(path: String, error: String) =
        dao.markFailed(path, FileState.FAILED_PERMANENT, error, clock())

    override suspend fun markProgress(path: String, bytesSent: Long) =
        dao.updateProgress(path, FileState.IN_PROGRESS, bytesSent, clock())

    override suspend fun markDone(path: String) {
        val entry = dao.get(path)
        dao.updateProgress(path, FileState.DONE, entry?.size ?: 0, clock())
    }

    override suspend fun markTransientFailure(path: String, error: String) =
        dao.markFailed(path, FileState.FAILED_TRANSIENT, error, clock())

    override suspend fun markPermanentFailure(path: String, error: String) =
        dao.markFailed(path, FileState.FAILED_PERMANENT, error, clock())

    override suspend fun nextBatch(limit: Int): List<QueuedFile> =
        dao.nextBatch(limit).mapNotNull { entry ->
            val absolute = absolutePaths[entry.path] ?: return@mapNotNull null
            QueuedFile(
                canonicalPath = entry.path,
                absolutePath = absolute,
                size = entry.size,
                mtime = entry.mtime,
                bytesSent = entry.bytesSent,
                attempts = entry.attempts,
            )
        }

    override suspend fun pendingCount(): Int = dao.countWaiting()
}

/** The journal in the database, trimmed to the last two hundred events. */
class RoomJournal(
    private val dao: LogEventDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncJournal {

    override suspend fun record(level: JournalLevel, code: String, message: String) {
        dao.insert(
            LogEventEntity(
                at = clock(),
                level = when (level) {
                    JournalLevel.INFO -> LogLevel.INFO
                    JournalLevel.WARN -> LogLevel.WARN
                    JournalLevel.ERROR -> LogLevel.ERROR
                },
                code = code,
                message = message,
            )
        )
        dao.trim()
    }
}

/** The conversation with the server, bound to the specific route that won the race. */
class ApiSyncTransport(
    private val api: FilumApiClient,
    private val endpoint: ServerEndpoint,
) : SyncTransport {

    override suspend fun manifest(request: ManifestRequest): ApiResult<ManifestResponse> =
        api.manifest(endpoint, request)

    override suspend fun putFile(
        canonicalPath: String,
        file: File,
        size: Long,
        mtime: Long,
        offset: Long,
        length: Long,
        digest: String?,
    ): ApiResult<FileChunkResponse> =
        api.putFile(endpoint, canonicalPath, file, size, mtime, offset, length, digest)

    override suspend fun putBundle(entries: List<BundleEntry>): ApiResult<BundleResponse> =
        api.putBundle(endpoint, entries)
}
