package pl.wspa.filum.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The client's data model (architecture §3.2).
 *
 * The database on the phone holds **the queue and the history**, not the truth about what
 * the backup contains. The source of truth about what the server has is the server's disk
 * (ADR-003) - here we keep only what allows work to resume after the process is killed.
 */

/**
 * A paired server. This version supports one, but the primary key is explicit so that
 * adding a second would not need a migration that breaks existing installations.
 */
@Entity(tableName = "paired_server")
data class PairedServerEntity(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val label: String,
    /** The server's public key fingerprint - its identity (ADR-006). */
    val serverPin: String,
    /** This phone's key alias in the Android Keystore. */
    val keyAlias: String,
    val serverInstanceId: String,
    val pairedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1L
    }
}

/**
 * One route to the server. The route set replaces a single "server address" (ADR-006)
 * and updates itself from the ping response (protocol §4.4).
 */
@Entity(tableName = "endpoint", primaryKeys = ["host", "port"])
data class EndpointEntity(
    val host: String,
    val port: Int,
    val kind: String,
    /** When it last worked. More recent and faster ones are tried first. */
    val lastOkAt: Long? = null,
    val lastRttMillis: Long? = null,
    val lastFailureAt: Long? = null,
)

enum class FileState {
    PENDING,
    IN_PROGRESS,
    DONE,

    /** Network, overload, no space - it returns to the queue. */
    FAILED_TRANSIENT,

    /** Refused by the server (422) or unreadable - we do not retry. */
    FAILED_PERMANENT,

    /** Skipped deliberately: an excluded directory, or a file being changed right now. */
    SKIPPED,
}

/**
 * A queue entry. A file's identity is the triple (path, size, mtime) - with no content
 * digest (I5, ADR-002).
 *
 * The key is the path alone, so a change of size or mtime overwrites the entry: that is
 * the same file in a new version, not a second file.
 */
@Entity(
    tableName = "file_entry",
    indices = [Index("state"), Index("updatedAt")],
)
data class FileEntryEntity(
    @PrimaryKey val path: String,
    val size: Long,
    val mtime: Long,
    val state: FileState,
    /** How many bytes the server has confirmed - the resume point (protocol §5.3). */
    val bytesSent: Long = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long,
)

@Entity(tableName = "sync_session")
data class SyncSessionEntity(
    @PrimaryKey val id: String,
    /** "full" or "delta" - the manifest mode (protocol §5.2). */
    val mode: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
    val filesFailed: Int = 0,
    val bytesSent: Long = 0,
    val result: String? = null,
)

/**
 * Scan progress. `lastGeneration` is the MediaStore counter: it allows asking what has
 * changed since last time in a single query, rather than walking a hundred thousand
 * files (architecture §3.3).
 */
@Entity(tableName = "scan_state")
data class ScanStateEntity(
    @PrimaryKey val volume: String,
    val lastGeneration: Long = 0,
    val lastFullScanAt: Long? = null,
)

@Entity(tableName = "exclusion")
data class ExclusionEntity(
    @PrimaryKey val pathPrefix: String,
    val enabled: Boolean = true,
)

/**
 * Foreground service budget usage, in hourly buckets.
 *
 * Android 15 grants the `dataSync` type six hours a day over a rolling window.
 * Hourly buckets make that window computable without keeping a full history.
 */
@Entity(tableName = "budget_window")
data class BudgetWindowEntity(
    @PrimaryKey val hourBucket: Long,
    val secondsUsed: Long,
)

enum class LogLevel { INFO, WARN, ERROR }

/**
 * The event journal shown in the application. Without it nothing that went wrong
 * overnight can be diagnosed - and overnight is when the application works.
 */
@Entity(tableName = "log_event", indices = [Index("at")])
data class LogEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val level: LogLevel,
    val code: String,
    val message: String,
)
