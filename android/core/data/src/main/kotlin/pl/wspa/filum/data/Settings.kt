package pl.wspa.filum.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Jak często aplikacja ma się budzić sama. */
enum class Schedule {
    /** Wyłącznie po naciśnięciu przycisku. */
    MANUAL,
    DAILY,
    WEEKLY,
}

/**
 * Ustawienia użytkownika. Jeden wiersz — nie ma tu nic, co wymagałoby tabeli
 * kluczy i wartości, a jawny schemat lepiej znosi zmiany niż luźne pary.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val schedule: Schedule = Schedule.MANUAL,

    /**
     * Cykl automatyczny wymaga ładowania i sieci nielimitowanej (§3.6 architektury).
     * Wyłączenie tych warunków jest możliwe, ale to świadoma decyzja użytkownika —
     * 200 GB przez sieć komórkową to nie jest coś, co ma się stać przypadkiem.
     */
    val requireCharging: Boolean = true,
    val unmeteredOnly: Boolean = true,
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
