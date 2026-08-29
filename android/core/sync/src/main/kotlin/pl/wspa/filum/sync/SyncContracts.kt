package pl.wspa.filum.sync

import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.protocol.FileChunkResponse
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.protocol.ManifestResponse
import pl.wspa.filum.scan.ScannedFile
import java.io.File

/**
 * Kontrakty silnika synchronizacji.
 *
 * Silnik nie zna ani OkHttpa, ani Rooma. Nie jest to abstrakcja dla samej abstrakcji:
 * decyzje, które tu zapadają — kiedy przerwać, co ponowić, co uznać za trwale nieudane —
 * są najtrudniejszą częścią całego klienta i muszą dać się przetestować bez sieci,
 * bez bazy i bez emulatora.
 */

/** Rozmowa z serwerem, już związana z konkretną trasą. */
interface SyncTransport {

    suspend fun manifest(request: ManifestRequest): ApiResult<ManifestResponse>

    suspend fun putFile(
        canonicalPath: String,
        file: File,
        size: Long,
        mtime: Long,
        offset: Long,
        length: Long,
        digest: String?,
    ): ApiResult<FileChunkResponse>
}

/** Pozycja kolejki widziana przez silnik. */
data class QueuedFile(
    val canonicalPath: String,
    val absolutePath: String,
    val size: Long,
    val mtime: Long,
    /** Ile bajtów serwer już potwierdził — punkt wznowienia. */
    val bytesSent: Long,
    val attempts: Int,
)

/** Trwały stan kolejki. Musi przeżyć zabicie procesu — na tym polega wznawialność. */
interface SyncQueue {

    suspend fun enqueue(files: List<ScannedFile>)

    /** Serwer już to ma; nic nie wysyłamy. */
    suspend fun markAlreadyOnServer(paths: List<String>)

    /** Serwer podał punkt wznowienia dla tych ścieżek. */
    suspend fun markNeeded(offsets: Map<String, Long>)

    suspend fun markRejected(path: String, error: String)

    suspend fun markProgress(path: String, bytesSent: Long)

    suspend fun markDone(path: String)

    suspend fun markTransientFailure(path: String, error: String)

    suspend fun markPermanentFailure(path: String, error: String)

    suspend fun nextBatch(limit: Int): List<QueuedFile>

    suspend fun pendingCount(): Int
}

enum class JournalLevel { INFO, WARN, ERROR }

/** Dziennik widoczny w aplikacji. Bez niego nie da się zdiagnozować nocnego cyklu. */
interface SyncJournal {
    suspend fun record(level: JournalLevel, code: String, message: String)
}

/**
 * Czy wolno jeszcze pracować.
 *
 * Jedno wywołanie zamiast przekazywania budżetu, sieci i stanu ładowania osobno —
 * silnik nie musi wiedzieć, **dlaczego** ma skończyć, tylko że ma to zrobić czysto.
 */
fun interface Deadline {
    fun reached(): Boolean
}

data class SyncConfig(
    /** Limit z odpowiedzi na ping; klient nie zakłada własnego (§5.1 protokołu). */
    val manifestBatch: Int = 2000,

    /**
     * Największy fragment jednego żądania PUT.
     *
     * Dzielimy duże pliki, żeby pojedyncze żądanie nie trwało godzinami na wolnym
     * łączu i żeby dało się sprawdzić budżet w środku wielogigabajtowego nagrania.
     */
    val chunkBytes: Long = 32L * 1024 * 1024,

    /**
     * Próg liczenia skrótu kontrolnego (§9 protokołu).
     *
     * Skrót wymaga dodatkowego odczytu całego pliku, bo nagłówek musi powstać przed
     * wysłaniem ciała. Dla plików małych i średnich drugi odczyt idzie z pamięci
     * podręcznej systemu i jest praktycznie darmowy; dla wielogigabajtowego nagrania
     * podwoiłby operacje wejścia-wyjścia, więc go pomijamy.
     */
    val digestMaxBytes: Long = 64L * 1024 * 1024,

    /** Po tylu nieudanych podejściach uznajemy plik za trwale problematyczny. */
    val maxAttempts: Int = 5,
)

/**
 * Dlaczego cykl skończył się wcześniej.
 *
 * Typ, a nie napis: od tego zależy, czy praca ma zostać ponowiona, czy odłożona
 * do następnego okna. Rozstrzyganie o tym przez porównanie tekstu wyłożyłoby się
 * po cichu przy pierwszej zmianie komunikatu.
 */
enum class InterruptReason {
    /** Wyczerpany budżet pracy w tle. Kolejka czeka; ponawianie teraz nic nie da. */
    BUDGET,

    /** Zerwane połączenie. Warto spróbować ponownie, gdy sieć wróci. */
    CONNECTION,
}

sealed interface SyncOutcome {

    /** Kolejka pusta — wszystko, co było do wysłania, poszło. */
    data class Completed(val filesSent: Int, val bytesSent: Long) : SyncOutcome

    /** Skończyliśmy sami, w kontrolowanym momencie. */
    data class Interrupted(val reason: InterruptReason, val filesSent: Int, val bytesSent: Long) : SyncOutcome

    /** Nie dało się nawet uzgodnić manifestu. */
    data class Failed(val reason: String) : SyncOutcome
}
