package pl.wspa.filum.app

import pl.wspa.filum.crypto.DeviceIdentity
import pl.wspa.filum.crypto.FilumTls
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.net.EndpointKind
import pl.wspa.filum.net.EndpointRacer
import pl.wspa.filum.net.FilumApiClient
import pl.wspa.filum.net.FilumHttpClients
import pl.wspa.filum.net.RaceOutcome
import pl.wspa.filum.net.ServerEndpoint
import pl.wspa.filum.protocol.VerifyEntry
import pl.wspa.filum.scan.ExclusionRules
import pl.wspa.filum.scan.FilesystemScanner
import pl.wspa.filum.scan.ScannedFile
import pl.wspa.filum.sync.JournalLevel
import java.io.File
import java.security.MessageDigest

sealed interface VerificationResult {
    data class Done(val checked: Int, val corrupted: List<String>, val missing: List<String>) : VerificationResult
    data class Failed(val reason: String) : VerificationResult
}

/**
 * Kontrola integralności kopii (§5.5 protokołu, ADR-002).
 *
 * Wykrywanie zmian celowo nie używa skrótów — liczenie SHA-256 z 200 GB przy każdym
 * cyklu jest nie do przyjęcia energetycznie. Ta operacja odpowiada jednak na inne
 * pytanie: czy to, co leży na dysku komputera, jest nadal tym, co tam wysłaliśmy.
 * Nośnik potrafi po cichu przekłamać bajt, a kopia zapasowa, która o tym nie wie,
 * jest kopią złudzenia.
 *
 * Sprawdzamy **próbkę**, nie całość. Pełna weryfikacja 200 GB oznaczałaby odczyt
 * całej pamięci telefonu i całego dysku serwera; losowa próbka wykrywa systematyczne
 * uszkodzenie nośnika przy koszcie liczonym w sekundach.
 */
class VerificationRunner(private val container: AppContainer) {

    suspend fun verifySample(sampleSize: Int = DEFAULT_SAMPLE): VerificationResult {
        val server = container.database.pairedServers().get()
            ?: return VerificationResult.Failed("Aplikacja nie jest sparowana z serwerem.")

        val identity = DeviceIdentity.loadOrCreate(server.keyAlias)
        val clients = FilumHttpClients(
            tls = FilumTls.bundle(server.serverPin, identity),
            binder = container.networkBinder,
        )

        val endpoint = findServer(clients)
            ?: return VerificationResult.Failed("Nie znaleziono serwera.")

        val sample = sample(sampleSize)
        if (sample.isEmpty()) return VerificationResult.Failed("Skan nie znalazł plików do sprawdzenia.")

        val entries = sample.mapNotNull { file ->
            digestOf(File(file.absolutePath))?.let { VerifyEntry(file.canonicalPath, it) }
        }

        val api = FilumApiClient(clients.forKind(endpoint.kind))

        return when (val result = api.verify(endpoint, entries)) {
            is ApiResult.Ok -> {
                val response = result.value
                journal(
                    if (response.mismatch.isEmpty()) JournalLevel.INFO else JournalLevel.ERROR,
                    "verified",
                    "Sprawdzono ${entries.size} plików: ${response.ok.size} zgodnych, " +
                        "${response.mismatch.size} uszkodzonych, ${response.missing.size} brakujących.",
                )
                VerificationResult.Done(entries.size, response.mismatch, response.missing)
            }

            is ApiResult.Failure -> VerificationResult.Failed(result.message)
            is ApiResult.Unreachable -> VerificationResult.Failed(result.message)
        }
    }

    private suspend fun findServer(clients: FilumHttpClients): ServerEndpoint? {
        val candidates = container.database.endpoints().all().mapNotNull { entity ->
            val kind = runCatching { EndpointKind.valueOf(entity.kind) }.getOrDefault(EndpointKind.MANUAL)
            ServerEndpoint(entity.host, entity.port, kind)
        }

        val race = EndpointRacer(
            probe = { endpoint -> FilumApiClient(clients.forProbe(endpoint.kind)).ping(endpoint) }
        ).fastest(candidates)

        return (race as? RaceOutcome.Reached)?.endpoint
    }

    /**
     * Losowa próbka z bieżącego skanu.
     *
     * Losowa, nie „pierwsze N": uszkodzenie nośnika nie zaczyna się od początku
     * alfabetu, a stała próbka sprawdzałaby w kółko te same pliki.
     */
    private suspend fun sample(size: Int): List<ScannedFile> {
        val exclusions = ExclusionRules(
            userPrefixes = container.database.exclusions().active().map { it.pathPrefix }
        )
        val root = File(pl.wspa.filum.scan.Environment.externalStorageRootPath())

        return FilesystemScanner(root, exclusions).walk()
            .filter { it.size <= MAX_SAMPLE_FILE_BYTES }
            .toList()
            .shuffled()
            .take(size)
    }

    private fun digestOf(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private suspend fun journal(level: JournalLevel, code: String, message: String) =
        RoomJournal(container.database.log()).record(level, code, message)

    private companion object {
        /** Limit serwera na jedno żądanie to 256 pozycji (§5.5). */
        const val DEFAULT_SAMPLE = 100

        /**
         * Pomijamy w próbce wielkie pliki. Skrót liczy telefon **i** serwer, więc
         * kilka nagrań wideo zamieniłoby sprawdzenie próbki w kilkuminutowy odczyt
         * po obu stronach.
         */
        const val MAX_SAMPLE_FILE_BYTES = 32L * 1024 * 1024
    }
}
