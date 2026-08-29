package pl.wspa.filum.sync

import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.protocol.FailureKind
import pl.wspa.filum.protocol.ManifestEntry
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.scan.ScannedFile
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/**
 * Silnik jednego cyklu synchronizacji.
 *
 * Całość jest zbudowana wokół jednej zasady: **każdy krok zapisuje stan**. Zabicie
 * procesu w dowolnym momencie kosztuje najwyżej jeden fragment pliku, bo wznawialność
 * działa na trzech poziomach — w środku pliku (przesunięcie), w środku kolejki
 * (baza przeżywa restart) i w środku kampanii (200 GB rozłożone na kilka nocy).
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
                files = batch.map { ManifestEntry(it.canonicalPath, it.size, it.mtimeSeconds) },
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
                return SyncOutcome.Interrupted("budżet", filesSent, bytesSent)
            }
        }

        return null
    }

    private suspend fun applyManifest(
        batch: List<ScannedFile>,
        response: pl.wspa.filum.protocol.ManifestResponse,
    ) {
        val needed = response.needed.associate { it.path to it.offset }
        val rejected = response.rejected.associate { it.path to it.error }

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

            for (entry in batch) {
                if (deadline.reached()) return stopOnBudget()

                when (send(entry, deadline)) {
                    SendResult.SENT -> filesSent++
                    SendResult.INTERRUPTED ->
                        return SyncOutcome.Interrupted("połączenie", filesSent, bytesSent)

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
        return SyncOutcome.Interrupted("budżet", filesSent, bytesSent)
    }

    private enum class SendResult { SENT, SKIPPED, FAILED, INTERRUPTED }

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

        // Skrót liczymy tylko wtedy, gdy plik idzie w całości w tej sesji — przy
        // wznowieniu trzeba by go odczytać od początku, co przeczy sensowi wznawiania (§9).
        val digest = if (offset == 0L && entry.size <= config.digestMaxBytes) sha256(file) else null

        while (offset < entry.size) {
            if (deadline.reached()) return SendResult.INTERRUPTED

            val length = min(config.chunkBytes, entry.size - offset)
            val isFinal = offset + length >= entry.size

            val result = transport.putFile(
                canonicalPath = entry.canonicalPath,
                file = file,
                size = entry.size,
                mtime = entry.mtime,
                offset = offset,
                length = length,
                // Serwer sprawdza skrót przy domknięciu transferu, więc ma sens
                // wyłącznie na ostatnim fragmencie (§8.2 protokołu).
                digest = if (isFinal) digest else null,
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
        const val UPLOAD_BATCH = 64

        /** Ile razy dajemy serwerowi poprawić nasze wyobrażenie o przesunięciu. */
        const val MAX_OFFSET_CORRECTIONS = 3
    }
}
