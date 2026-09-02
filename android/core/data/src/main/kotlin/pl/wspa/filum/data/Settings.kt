package pl.wspa.filum.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import pl.wspa.filum.protocol.IntegrityPolicy

/** How often the application wakes itself. */
enum class Schedule {
    /** Only when the button is pressed. */
    MANUAL,
    DAILY,
    WEEKLY,
}

/**
 * The user's settings. One row — there is nothing here that would need a key-value table,
 * and an explicit schema survives change better than loose pairs.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val schedule: Schedule = Schedule.MANUAL,

    /**
     * The automatic cycle requires charging and an unmetered network (architecture §3.6).
     * These conditions can be turned off, but that is a deliberate decision by the user —
     * uploading a whole library over cellular is not to happen by accident.
     */
    val requireCharging: Boolean = true,
    val unmeteredOnly: Boolean = true,

    /**
     * How we detect that a file changed (ADR-016).
     *
     * Fast mode by default, because thorough mode reads the whole library on every cycle
     * and on a large set that gets expensive. Turning it on is a deliberate choice, just as
     * turning off the conditions above is — with the difference that here you pay in time
     * and gain the ability to detect a metadata-preserving substitution.
     */
    val integrityPolicy: IntegrityPolicy = IntegrityPolicy.FAST,
) {
    companion object {
        const val SINGLETON_ID = 1L
    }
}

@Dao
interface SettingsDao {

    @Query("SELECT * FROM settings WHERE id = :id")
    suspend fun get(id: Long = SettingsEntity.SINGLETON_ID): SettingsEntity?

    @Query("SELECT * FROM settings WHERE id = :id")
    fun observe(id: Long = SettingsEntity.SINGLETON_ID): Flow<SettingsEntity?>

    @Upsert
    suspend fun save(settings: SettingsEntity)
}
