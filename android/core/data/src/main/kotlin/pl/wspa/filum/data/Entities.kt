package pl.wspa.filum.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Model danych klienta (§3.2 architektury).
 *
 * Baza po stronie telefonu trzyma **kolejkę i historię**, a nie prawdę o zawartości
 * kopii zapasowej. Źródłem prawdy o tym, co serwer ma, pozostaje dysk serwera (ADR-003) —
 * tutaj przechowujemy tylko to, co pozwala wznowić pracę po zabiciu procesu.
 */

/**
 * Sparowany serwer. W tej wersji obsługujemy jeden, ale klucz główny jest jawny,
 * żeby dołożenie drugiego nie wymagało migracji rozbijającej istniejące instalacje.
 */
@Entity(tableName = "paired_server")
data class PairedServerEntity(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val label: String,
    /** Odcisk klucza publicznego serwera — jego tożsamość (ADR-006). */
    val serverPin: String,
    /** Alias klucza tego telefonu w Android Keystore. */
    val keyAlias: String,
    val serverInstanceId: String,
    val pairedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1L
    }
}

/**
 * Jedna trasa do serwera. Zbiór tras zastępuje pojedynczy „adres serwera" (ADR-006)
 * i aktualizuje się sam z odpowiedzi na ping (§4.4 protokołu).
 */
@Entity(tableName = "endpoint", primaryKeys = ["host", "port"])
data class EndpointEntity(
    val host: String,
    val port: Int,
    val kind: String,
    /** Kiedy ostatnio zadziałała. Nowsze i szybsze próbujemy pierwsze. */
    val lastOkAt: Long? = null,
    val lastRttMillis: Long? = null,
    val lastFailureAt: Long? = null,
)

enum class FileState {
    PENDING,
    IN_PROGRESS,
    DONE,

    /** Sieć, przeciążenie, brak miejsca — wróci do kolejki. */
    FAILED_TRANSIENT,

    /** Odrzucone przez serwer (422) albo nieczytelne — nie ponawiamy. */
    FAILED_PERMANENT,

    /** Pominięte świadomie: wykluczony katalog albo plik zmieniany w tej chwili. */
    SKIPPED,
}

/**
 * Pozycja kolejki. Tożsamość pliku to trójka (ścieżka, rozmiar, mtime) — bez skrótu
 * zawartości (I5, ADR-002).
 *
 * Kluczem jest sama ścieżka, więc zmiana rozmiaru albo mtime nadpisuje wpis: to ten
 * sam plik w nowej wersji, a nie drugi plik.
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
    /** Ile bajtów serwer już potwierdził — punkt wznowienia (§5.3 protokołu). */
    val bytesSent: Long = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long,
)

@Entity(tableName = "sync_session")
data class SyncSessionEntity(
    @PrimaryKey val id: String,
    /** "full" albo "delta" — tryb manifestu (§5.2 protokołu). */
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
 * Postęp skanowania. `lastGeneration` to licznik z MediaStore: pozwala zapytać
 * „co się zmieniło od poprzedniego razu" jednym zapytaniem, zamiast obchodzić
 * sto tysięcy plików (§3.3 architektury).
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
 * Zużycie budżetu usługi pierwszoplanowej, w kubełkach godzinowych.
 *
 * Android 15 daje typowi `dataSync` sześć godzin na dobę w oknie kroczącym.
 * Kubełki godzinowe pozwalają policzyć to okno bez trzymania pełnej historii.
 */
@Entity(tableName = "budget_window")
data class BudgetWindowEntity(
    @PrimaryKey val hourBucket: Long,
    val secondsUsed: Long,
)

enum class LogLevel { INFO, WARN, ERROR }

/**
 * Dziennik zdarzeń widoczny w aplikacji. Bez niego nie da się zdiagnozować niczego,
 * co poszło nie tak w nocy — a właśnie wtedy aplikacja pracuje.
 */
@Entity(tableName = "log_event", indices = [Index("at")])
data class LogEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val level: LogLevel,
    val code: String,
    val message: String,
)
