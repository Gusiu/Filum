package pl.wspa.filum.sync

import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.net.BundleEntry
import pl.wspa.filum.protocol.FailureKind
import pl.wspa.filum.protocol.IntegrityPolicy
import pl.wspa.filum.protocol.ManifestEntry
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.scan.ScannedFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.min

/**
 * Silnik jednego cyklu synchronizacji.
 *
 * Całość jest zbudowana wokół jednej zasady: **każdy krok zapisuje stan**. Zabicie
 * procesu w dowolnym momencie kosztuje najwyżej jeden fragment pliku, bo wznawialność
 * działa na trzech poziomach — w środku pliku (przesunięcie), w środku kolejki
 * (baza przeżywa restart) i w środku kampanii (pierwszy zrzut rozłożony na kilka nocy).
 *
 * Dzięki temu limit sześciu godzin usługi pierwszoplanowej przestaje być problemem:
 * silnik robi tyle, ile zdąży, i kończy sam.
 */
class SyncEngine(
    private val transport: SyncTransport,
    private val queue: SyncQueue,
    private val journal: SyncJournal,
    private val config: SyncConfig = SyncConfig(),
) {

    private var filesSent = 0
    private var bytesSent = 0L

    suspend fun run(
        sessionId: String,
        mode: String,
        files: Sequence<ScannedFile>,
        deadline: Deadline,
    ): SyncOutcome {
        filesSent = 0
        bytesSent = 0

        val reconciled = reconcile(sessionId, mode, files, deadline)
        if (reconciled != null) return reconciled

        return upload(deadline)
    }

    // --- uzgodnienie manifestu (§5.2 protokołu) ---

    /** Zwraca wynik, gdy trzeba przerwać; `null`, gdy można przejść do wysyłki. */
    private suspend fun reconcile(
        sessionId: String,
        mode: String,
        files: Sequence<ScannedFile>,
        deadline: Deadline,
    ): SyncOutcome? {
        val batches = files.chunked(config.manifestBatch).iterator()
        var index = 0

        if (!batches.hasNext()) {
            journal.record(JournalLevel.INFO, "manifest_empty", "Skan nie znalazł żadnych plików.")
            return null
        }

        while (batches.hasNext()) {
            val batch = batches.next()
            val isLast = !batches.hasNext()

            queue.enqueue(batch)

            val request = ManifestRequest(
                session = sessionId,
                mode = mode,
                batch = index,
                final = isLast,
                files = batch.map {
                    ManifestEntry(it.canonicalPath, it.size, it.mtimeSeconds, digestFor(it, deadline))
                },
            )

            when (val response = transport.manifest(request)) {
                is ApiResult.Ok -> applyManifest(batch, response.value)

                is ApiResult.Unreachable -> {
                    journal.record(JournalLevel.WARN, "manifest_unreachable", response.message)
                    return SyncOutcome.Failed("Serwer nieosiągalny: ${response.message}")
                }

                is ApiResult.Failure -> {
                    journal.record(JournalLevel.ERROR, response.code, response.message)
                    return SyncOutcome.Failed("Serwer odrzucił manifest: ${response.message}")
                }
            }

            index++

            // Uzgodnienie stu tysięcy plików to pięćdziesiąt żądań. Przy wyczerpanym
            // budżecie kończymy tutaj — kolejka jest już częściowo zapisana, więc
            // następny cykl podejmie pracę od tego miejsca.
            if (deadline.reached()) {
                journal.record(
                    JournalLevel.INFO, "deadline_manifest",
                    "Koniec budżetu w trakcie uzgadniania manifestu, partia $index.",
                )
                return SyncOutcome.Interrupted(InterruptReason.BUDGET, filesSent, bytesSent)
            }
        }

        return null
    }

    /**
     * Skrót do manifestu — liczony wyłącznie w trybie dokładnym.
     *
     * Termin sprawdzamy tutaj, a nie tylko między partiami: partia liczy do dwóch
     * tysięcy pozycji, a przy dużych plikach jej zahaszowanie potrafi trwać dłużej
     * niż całe pozostałe okno pracy w tle. Pominięcie skrótu jest bezpieczne —
     * serwer potraktuje pozycję jak w trybie szybkim.
     */
    private fun digestFor(file: ScannedFile, deadline: Deadline): String? {
        if (config.integrityPolicy != IntegrityPolicy.THOROUGH) return null
        if (deadline.reached()) return null

        return try {
            sha256(File(file.absolutePath))
        } catch (e: IOException) {
            // Plik zniknął albo jest nieczytelny. Nie ma sensu przerywać uzgadniania —
            // transfer i tak zgłosi to samo, tylko z konkretniejszym komunikatem.
            null
        }
    }

    private suspend fun applyManifest(
        batch: List<ScannedFile>,
        response: pl.wspa.filum.protocol.ManifestResponse,
    ) {
        val needed = response.needed.associate { it.path to it.offset }
        val rejected = response.rejected.associate { it.path to it.error }

        // Zawartość zmieniona przy nietkniętych metadanych. Plik i tak trafi do kolejki,
        // bo serwer wpisał go do "needed" — ale to jest zdarzenie, o którym użytkownik
        // ma się dowiedzieć, a nie zwykła aktualizacja.
        for (path in response.divergent) {
            journal.record(
                JournalLevel.WARN, "integrity_divergent",
                "$path: zawartość różni się od zapisanej w rejestrze mimo zgodnego rozmiaru i czasu modyfikacji.",
            )
        }

        // Serwer podaje liczbę posiadanych plików, ale nie ich listę. Wszystko, czego
        // nie ma ani w "needed", ani w "rejected", serwer już ma — i nie wysyłamy tego.
        val alreadyThere = batch.map { it.canonicalPath }
            .filterNot { it in needed || it in rejected }

        if (alreadyThere.isNotEmpty()) queue.markAlreadyOnServer(alreadyThere)
        if (needed.isNotEmpty()) queue.markNeeded(needed)

        for ((path, error) in rejected) {
            queue.markRejected(path, error)
            journal.record(JournalLevel.WARN, "path_rejected", "$path: $error")
        }
    }

    // --- wysyłka ---

    private suspend fun upload(deadline: Deadline): SyncOutcome {
        while (true) {
            if (deadline.reached()) return stopOnBudget()

            val batch = queue.nextBatch(UPLOAD_BATCH)
            if (batch.isEmpty()) {
                return SyncOutcome.Completed(filesSent, bytesSent)
            }

            // Małe pliki idą paczkami, duże pojedynczo. Przy stu tysiącach plików,
            // z których większość jest drobna, narzut żądania HTTP zaczyna dominować
            // nad transferem danych (ADR-008).
            val (small, large) = batch.partition { it.size <= config.smallFileThreshold && it.bytesSent == 0L }

            for (group in group(small)) {
                if (deadline.reached()) return stopOnBudget()

                when (sendBundle(group)) {
                    SendResult.INTERRUPTED ->
                        return SyncOutcome.Interrupted(InterruptReason.CONNECTION, filesSent, bytesSent)

                    else -> Unit
                }
            }

            for (entry in large) {
                if (deadline.reached()) return stopOnBudget()

                when (send(entry, deadline)) {
                    SendResult.SENT -> filesSent++
                    SendResult.INTERRUPTED ->
                        return SyncOutcome.Interrupted(InterruptReason.CONNECTION, filesSent, bytesSent)

                    SendResult.SKIPPED, SendResult.FAILED -> Unit
                }
            }
        }
    }

    /**
     * Zakończenie z powodu budżetu. Zawsze przez tę jedną drogę, bo cykl nocny bez
     * wpisu w dzienniku jest nie do zdiagnozowania — użytkownik widzi tylko, że rano
     * część plików nie doszła, i nie ma jak sprawdzić dlaczego.
     */
    private suspend fun stopOnBudget(): SyncOutcome {
        journal.record(
            JournalLevel.INFO, "deadline_upload",
            "Koniec budżetu; kończę czysto po $filesSent plikach ($bytesSent B).",
        )
        return SyncOutcome.Interrupted(InterruptReason.BUDGET, filesSent, bytesSent)
    }

    private enum class SendResult { SENT, SKIPPED, FAILED, INTERRUPTED }

    /**
     * Dzieli pliki na paczki mieszczące się w limitach z odpowiedzi na ping.
     *
     * Plik większy od limitu paczki nie może w niej pojechać — trafia do niej sam,
     * a i tak zostanie odrzucony przez próg rozmiaru wyżej; tutaj pilnujemy tylko,
     * żeby nie zbudować paczki, której serwer nie przyjmie.
     */
    private fun group(files: List<QueuedFile>): List<List<QueuedFile>> {
        val groups = mutableListOf<List<QueuedFile>>()
        var current = mutableListOf<QueuedFile>()
        var bytes = 0L

        for (file in files) {
            val wouldExceed = bytes + file.size > config.bundleMaxBytes ||
                current.size >= config.bundleMaxCount

            if (wouldExceed && current.isNotEmpty()) {
                groups += current
                current = mutableListOf()
                bytes = 0
            }

            current += file
            bytes += file.size
        }

        if (current.isNotEmpty()) groups += current
        return groups
    }

    private suspend fun sendBundle(group: List<QueuedFile>): SendResult {
        val entries = mutableListOf<BundleEntry>()
        val byPath = mutableMapOf<String, QueuedFile>()

        for (queued in group) {
            val file = File(queued.absolutePath)

            if (!file.isFile) {
                queue.markPermanentFailure(queued.canonicalPath, "plik już nie istnieje")
                continue
            }
            if (file.length() != queued.size || Math.floorDiv(file.lastModified(), 1000L) != queued.mtime) {
                queue.markTransientFailure(queued.canonicalPath, "plik zmienił się po skanie")
                continue
            }

            entries += BundleEntry(
                canonicalPath = queued.canonicalPath,
                file = file,
                size = queued.size,
                mtime = queued.mtime,
                digest = sha256(file),
            )
            byPath[queued.canonicalPath] = queued
        }

        if (entries.isEmpty()) return SendResult.SKIPPED

        return when (val result = transport.putBundle(entries)) {
            is ApiResult.Ok -> {
                applyBundleResults(result.value.results, byPath)
                SendResult.SENT
            }

            is ApiResult.Unreachable -> {
                // Paczka nie doszła w całości. Wszystkie jej pliki wracają do kolejki —
                // nie mamy pojęcia, ile z nich serwer zdążył zapisać, a ponowna
                // wysyłka niczego nie psuje, bo manifest i tak je odsieje.
                entries.forEach { queue.markTransientFailure(it.canonicalPath, result.message) }
                SendResult.INTERRUPTED
            }

            is ApiResult.Failure -> {
                // Uszkodzone ramkowanie albo przekroczony limit unieważnia całą paczkę.
                entries.forEach { queue.markTransientFailure(it.canonicalPath, result.code) }
                journal.record(JournalLevel.WARN, result.code, "Paczka odrzucona: ${result.message}")
                SendResult.FAILED
            }
        }
    }

    private suspend fun applyBundleResults(
        results: List<pl.wspa.filum.protocol.BundleFileResult>,
        byPath: Map<String, QueuedFile>,
    ) {
        val reported = results.map { it.path }.toSet()

        for (result in results) {
            val queued = byPath[result.path] ?: continue

            if (result.ok) {
                queue.markDone(result.path)
                filesSent++
                bytesSent += queued.size
                continue
            }

            // Odrzucenie pojedynczego pliku w paczce ma tę samą wagę co przy PUT:
            // zła ścieżka jest trwała, reszta warta ponowienia.
            val error = result.error.orEmpty()
            if (error in PERMANENT_BUNDLE_ERRORS) {
                queue.markPermanentFailure(result.path, error)
                journal.record(JournalLevel.WARN, error, "${result.path}: odrzucone w paczce")
            } else {
                queue.markTransientFailure(result.path, error)
            }
        }

        // Serwer nie wspomniał o pliku, który wysłaliśmy. Nie zakładamy powodzenia —
        // wraca do kolejki i wyjaśni się przy najbliższym manifeście.
        for ((path, _) in byPath) {
            if (path !in reported) queue.markTransientFailure(path, "brak wyniku w odpowiedzi")
        }
    }

    private suspend fun send(entry: QueuedFile, deadline: Deadline): SendResult {
        val file = File(entry.absolutePath)

        if (!file.isFile) {
            // Użytkownik usunął plik między skanem a wysyłką. To nie jest błąd —
            // kopia z poprzednich cykli i tak zostaje na serwerze (niezmiennik I1).
            queue.markPermanentFailure(entry.canonicalPath, "plik już nie istnieje")
            return SendResult.SKIPPED
        }

        val currentMtime = Math.floorDiv(file.lastModified(), 1000L)
        if (file.length() != entry.size || currentMtime != entry.mtime) {
            // Plik zmienił się po skanie. Wysyłanie go teraz skleiłoby dwie wersje;
            // wróci do kolejki przy następnym skanie, już z aktualną tożsamością.
            queue.markTransientFailure(entry.canonicalPath, "plik zmienił się po skanie")
            journal.record(
                JournalLevel.INFO, "file_changed",
                "${entry.canonicalPath} zmienił się po skanie; wróci w następnym cyklu.",
            )
            return SendResult.SKIPPED
        }

        if (entry.attempts >= config.maxAttempts) {
            queue.markPermanentFailure(entry.canonicalPath, "przekroczono limit prób")
            journal.record(
                JournalLevel.ERROR, "too_many_attempts",
                "${entry.canonicalPath}: ${config.maxAttempts} nieudanych podejść, odkładam.",
            )
            return SendResult.FAILED
        }

        return transfer(entry, file, deadline)
    }

    private suspend fun transfer(entry: QueuedFile, file: File, deadline: Deadline): SendResult {
        var offset = entry.bytesSent
        var mismatches = 0

        // Skrót liczymy zawsze, także przy wznowieniu. Wznawianie oszczędza transmisję,
        // a nie odczyt z pamięci telefonu — a serwer bez skrótu i tak niczego nie przyjmie
        // (ADR-016). Cena to jeden lokalny odczyt pliku; korzyść to wpis w rejestrze.
        val digest = sha256(file)

        while (offset < entry.size) {
            if (deadline.reached()) return SendResult.INTERRUPTED

            val length = min(config.chunkBytes, entry.size - offset)

            val result = transport.putFile(
                canonicalPath = entry.canonicalPath,
                file = file,
                size = entry.size,
                mtime = entry.mtime,
                offset = offset,
                length = length,
                // Skrót idzie z każdym fragmentem, choć serwer sprawdza go dopiero przy
                // domknięciu. Inaczej wymóg z ADR-016 odrzucałby fragmenty pośrednie,
                // a kilkadziesiąt bajtów nagłówka na 32 MB ciała nie robi różnicy.
                digest = digest,
            )

            when (result) {
                is ApiResult.Ok -> {
                    bytesSent += result.value.received
                    offset = result.value.offset
                    queue.markProgress(entry.canonicalPath, offset)

                    if (result.value.complete) {
                        queue.markDone(entry.canonicalPath)
                        return SendResult.SENT
                    }
                }

                is ApiResult.Unreachable -> {
                    queue.markTransientFailure(entry.canonicalPath, result.message)
                    return SendResult.INTERRUPTED
                }

                is ApiResult.Failure -> {
                    val expected = result.expectedOffset
                    if (expected != null && expected != offset) {
                        // Serwer wie lepiej, ile ma. Przestawiamy się na jego stan,
                        // ale nie w nieskończoność: krążenie oznaczałoby, że coś
                        // po drugiej stronie zmienia się w trakcie.
                        mismatches++
                        if (mismatches > MAX_OFFSET_CORRECTIONS) {
                            queue.markTransientFailure(entry.canonicalPath, "niestabilne przesunięcie")
                            return SendResult.FAILED
                        }

                        journal.record(
                            JournalLevel.INFO, "offset_corrected",
                            "${entry.canonicalPath}: wznawiam od $expected zamiast $offset.",
                        )
                        offset = expected
                        queue.markProgress(entry.canonicalPath, expected)
                        continue
                    }

                    return recordFailure(entry, result)
                }
            }
        }

        // Pętla wyszła bez potwierdzenia domknięcia — serwer policzył inaczej niż my.
        queue.markTransientFailure(entry.canonicalPath, "serwer nie potwierdził kompletności")
        return SendResult.FAILED
    }

    private suspend fun recordFailure(entry: QueuedFile, failure: ApiResult.Failure): SendResult =
        when (failure.kind) {
            FailureKind.PERMANENT -> {
                queue.markPermanentFailure(entry.canonicalPath, failure.code)
                journal.record(
                    JournalLevel.ERROR, failure.code,
                    "${entry.canonicalPath}: ${failure.message}",
                )
                SendResult.FAILED
            }

            FailureKind.TRANSIENT -> {
                queue.markTransientFailure(entry.canonicalPath, failure.code)
                journal.record(
                    JournalLevel.WARN, failure.code,
                    "${entry.canonicalPath}: ${failure.message}",
                )
                SendResult.FAILED
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Ile pozycji bierzemy z kolejki naraz. Mniej znaczy częstsze zapytania do bazy. */
        const val UPLOAD_BATCH = 256

        /** Błędy paczki, po których ponawianie nie ma sensu — to wada pliku, nie sieci. */
        val PERMANENT_BUNDLE_ERRORS = setOf(
            "absolute", "traversal", "dot_segment", "empty_segment", "nul", "path_too_long",
        )

        /** Ile razy dajemy serwerowi poprawić nasze wyobrażenie o przesunięciu. */
        const val MAX_OFFSET_CORRECTIONS = 3
    }
}
