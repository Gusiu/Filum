package pl.wspa.filum.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import pl.wspa.filum.protocol.IntegrityPolicy

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
     * wysłanie całej biblioteki przez sieć komórkową nie ma się zdarzyć przypadkiem.
     */
    val requireCharging: Boolean = true,
    val unmeteredOnly: Boolean = true,

    /**
     * Jak wykrywamy zmianę pliku (ADR-016).
     *
     * Domyślnie tryb szybki, bo tryb dokładny czyta całą bibliotekę przy każdym cyklu
     * i na dużym zbiorze bywa kosztowny. Włączenie go jest świadomym wyborem, tak samo
     * jak wyłączenie warunków powyżej — z tą różnicą, że tutaj płaci się czasem,
     * a zyskuje wykrywalność podmiany zachowującej metadane.
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
