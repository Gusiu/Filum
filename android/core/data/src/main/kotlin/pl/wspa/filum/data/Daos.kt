package pl.wspa.filum.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedServerDao {

    @Query("SELECT * FROM paired_server WHERE id = :id")
    suspend fun get(id: Long = PairedServerEntity.SINGLETON_ID): PairedServerEntity?

    @Query("SELECT * FROM paired_server WHERE id = :id")
    fun observe(id: Long = PairedServerEntity.SINGLETON_ID): Flow<PairedServerEntity?>

    @Upsert
    suspend fun save(server: PairedServerEntity)

    @Query("DELETE FROM paired_server")
    suspend fun clear()
}

@Dao
interface EndpointDao {

    /**
     * Routes in probing order: the ones that worked most recently first, and among those
     * the fastest. The race checks all of them in parallel anyway (§4.2), but the order
     * decides a tie and reads sensibly in the journal.
     */
    @Query(
        """
        SELECT * FROM endpoint
        ORDER BY (lastOkAt IS NULL), lastOkAt DESC, (lastRttMillis IS NULL), lastRttMillis ASC
        """
    )
    suspend fun all(): List<EndpointEntity>

    @Upsert
    suspend fun upsert(endpoints: List<EndpointEntity>)

    @Query("UPDATE endpoint SET lastOkAt = :at, lastRttMillis = :rtt WHERE host = :host AND port = :port")
    suspend fun markReached(host: String, port: Int, at: Long, rtt: Long)

    @Query("UPDATE endpoint SET lastFailureAt = :at WHERE host = :host AND port = :port")
    suspend fun markFailed(host: String, port: Int, at: Long)

    @Delete
    suspend fun delete(endpoint: EndpointEntity)

    @Query("DELETE FROM endpoint")
    suspend fun clear()
}

@Dao
interface FileEntryDao {

    @Upsert
    suspend fun upsert(entries: List<FileEntryEntity>)

    @Query("SELECT * FROM file_entry WHERE path = :path")
    suspend fun get(path: String): FileEntryEntity?

    /**
     * The next batch to send. Oldest entries first, so that a file which once lost its
     * place in a batch does not wait forever.
     */
    @Query(
        """
        SELECT * FROM file_entry
        WHERE state IN ('PENDING', 'FAILED_TRANSIENT')
        ORDER BY updatedAt ASC
        LIMIT :limit
        """
    )
    suspend fun nextBatch(limit: Int): List<FileEntryEntity>

    @Query("SELECT COUNT(*) FROM file_entry WHERE state = :state")
    suspend fun countByState(state: FileState): Int

    @Query("SELECT COUNT(*) FROM file_entry WHERE state IN ('PENDING', 'FAILED_TRANSIENT')")
    fun observePending(): Flow<Int>

    @Query("SELECT COALESCE(SUM(size - bytesSent), 0) FROM file_entry WHERE state IN ('PENDING', 'FAILED_TRANSIENT')")
    suspend fun remainingBytes(): Long

    @Query("UPDATE file_entry SET state = :state, bytesSent = :bytesSent, updatedAt = :at WHERE path = :path")
    suspend fun updateProgress(path: String, state: FileState, bytesSent: Long, at: Long)

    @Query(
        """
        UPDATE file_entry
        SET state = :state, attempts = attempts + 1, lastError = :error, updatedAt = :at
        WHERE path = :path
        """
    )
    suspend fun markFailed(path: String, state: FileState, error: String, at: Long)

    /**
     * Cleans up successfully finished entries older than the given moment.
     *
     * The queue is not an archive — the history lives in the journal and in the sessions.
     * Without it the table would grow by every one of a hundred thousand files and never shrink.
     */
    @Query("DELETE FROM file_entry WHERE state = 'DONE' AND updatedAt < :before")
    suspend fun pruneCompleted(before: Long): Int

    @Query("DELETE FROM file_entry")
    suspend fun clear()
}

@Dao
interface SyncSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun start(session: SyncSessionEntity)

    @Upsert
    suspend fun save(session: SyncSessionEntity)

    @Query("SELECT * FROM sync_session WHERE id = :id")
    suspend fun get(id: String): SyncSessionEntity?

    @Query("SELECT * FROM sync_session ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<SyncSessionEntity>>

    @Query("SELECT * FROM sync_session ORDER BY startedAt DESC LIMIT 1")
    suspend fun latest(): SyncSessionEntity?
}

@Dao
interface ScanStateDao {

    @Query("SELECT * FROM scan_state WHERE volume = :volume")
    suspend fun get(volume: String): ScanStateEntity?

    @Upsert
    suspend fun save(state: ScanStateEntity)

    @Query("DELETE FROM scan_state")
    suspend fun clear()
}

@Dao
interface ExclusionDao {

    @Query("SELECT * FROM exclusion ORDER BY pathPrefix")
    fun observeAll(): Flow<List<ExclusionEntity>>

    @Query("SELECT * FROM exclusion WHERE enabled = 1")
    suspend fun active(): List<ExclusionEntity>

    @Upsert
    suspend fun save(exclusion: ExclusionEntity)

    @Delete
    suspend fun delete(exclusion: ExclusionEntity)
}

@Dao
interface BudgetDao {

    @Query("SELECT COALESCE(SUM(secondsUsed), 0) FROM budget_window WHERE hourBucket >= :since")
    suspend fun secondsUsedSince(since: Long): Long

    @Query(
        """
        INSERT INTO budget_window (hourBucket, secondsUsed) VALUES (:bucket, :seconds)
        ON CONFLICT(hourBucket) DO UPDATE SET secondsUsed = secondsUsed + :seconds
        """
    )
    suspend fun add(bucket: Long, seconds: Long)

    @Query("DELETE FROM budget_window WHERE hourBucket < :before")
    suspend fun prune(before: Long)
}

@Dao
interface LogEventDao {

    @Insert
    suspend fun insert(event: LogEventEntity)

    @Query("SELECT * FROM log_event ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = LogEventDao.RETENTION): Flow<List<LogEventEntity>>

    @Query("SELECT * FROM log_event ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int = LogEventDao.RETENTION): List<LogEventEntity>

    /**
     * Trims the journal to the last [RETENTION] events. The log screen shows that many
     * anyway, and across a hundred thousand files an unbounded journal would grow faster
     * than the data itself.
     */
    @Query(
        """
        DELETE FROM log_event WHERE id NOT IN (
            SELECT id FROM log_event ORDER BY at DESC, id DESC LIMIT :keep
        )
        """
    )
    suspend fun trim(keep: Int = RETENTION)

    @Query("DELETE FROM log_event")
    suspend fun clear()

    companion object {
        const val RETENTION = 200
    }
}
