package pl.wspa.filum.app

import android.util.Log
import pl.wspa.filum.crypto.DeviceIdentity
import pl.wspa.filum.crypto.FilumTls
import pl.wspa.filum.data.EndpointEntity
import pl.wspa.filum.data.PairedServerEntity
import pl.wspa.filum.data.ScanStateEntity
import pl.wspa.filum.data.SyncSessionEntity
import pl.wspa.filum.net.EndpointKind
import pl.wspa.filum.net.EndpointRacer
import pl.wspa.filum.net.FilumApiClient
import pl.wspa.filum.net.FilumHttpClients
import pl.wspa.filum.net.MdnsDiscovery
import pl.wspa.filum.net.RaceOutcome
import pl.wspa.filum.net.ServerEndpoint
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.scan.ExclusionRules
import pl.wspa.filum.scan.FilesystemScanner
import pl.wspa.filum.scan.MediaStoreScanner
import pl.wspa.filum.scan.ScannedFile
import pl.wspa.filum.sync.Deadline
import pl.wspa.filum.sync.JournalLevel
import pl.wspa.filum.sync.SyncConfig
import pl.wspa.filum.sync.SyncEngine
import pl.wspa.filum.sync.SyncOutcome
import java.io.File
import java.util.UUID

sealed interface CycleResult {
    data class Done(val outcome: SyncOutcome, val endpoint: ServerEndpoint) : CycleResult
    data class NotPaired(val message: String = "Aplikacja nie jest sparowana z serwerem.") : CycleResult
    data class NoRoute(val failures: List<String>) : CycleResult
}

/**
 * Jeden pełny cykl: znajdź serwer, zeskanuj, uzgodnij manifest, wyślij.
 *
 * Kolejność jest istotna. Trasę wybieramy **przed** skanem, bo skan bez odbiorcy
 * to zmarnowana bateria; a odpowiedź na ping przy okazji odświeża zbiór tras (§4.4).
 */
class SyncRunner(private val container: AppContainer) {

    suspend fun runOnce(
        mode: String = ManifestRequest.MODE_DELTA,
        deadline: Deadline = Deadline { false },
    ): CycleResult {
        val server = container.database.pairedServers().get()
            ?: return CycleResult.NotPaired()

        val identity = DeviceIdentity.loadOrCreate(server.keyAlias)
        val clients = FilumHttpClients(
            tls = FilumTls.bundle(server.serverPin, identity),
            binder = container.networkBinder,
        )
        val api = FilumApiClient(clients.forKind(EndpointKind.LAN))

        val racer = EndpointRacer(
            probe = { endpoint -> FilumApiClient(clients.forProbe(endpoint.kind)).ping(endpoint) }
        )

        // Najpierw zapisane trasy — w zdecydowanej większości cykli któraś z nich
        // odpowie i nie ma powodu płacić za wykrywanie.
        var race = racer.fastest(loadCandidates(server))

        if (race !is RaceOutcome.Reached) {
            // Żadna zapisana trasa nie zadziałała. To jedyna sytuacja, w której
            // samoaktualizacja zbioru tras (§4.4) nie ma się od czego odbić:
            // telefon nie był w domu przez zmianę routera i nie ma sieci nakładkowej.
            val discovered = MdnsDiscovery(container.context).findByPin(server.serverPin)

            if (discovered.isNotEmpty()) {
                journalDiscovery(discovered)
                race = racer.fastest(discovered)
            }
        }

        val reached = when (race) {
            is RaceOutcome.Reached -> race
            is RaceOutcome.AllFailed -> return CycleResult.NoRoute(
                race.failures.map { "${it.endpoint}: ${it.reason}" }
            )

            RaceOutcome.NoCandidates -> return CycleResult.NoRoute(listOf("Brak zapisanych tras."))
        }

        rememberRoutes(reached)

        val journal = RoomJournal(container.database.log())
        journal.record(
            JournalLevel.INFO, "route_selected",
            "Połączono przez ${reached.endpoint} w ${reached.elapsedMillis} ms.",
        )

        val sessionId = UUID.randomUUID().toString()
        container.database.sessions().start(
            SyncSessionEntity(sessionId, mode, startedAt = System.currentTimeMillis())
        )

        val engine = SyncEngine(
            transport = ApiSyncTransport(FilumApiClient(clients.forKind(reached.endpoint.kind)), reached.endpoint),
            queue = container.queue,
            journal = journal,
            config = SyncConfig(manifestBatch = reached.ping.limits.manifestBatch),
        )

        val (files, usedDelta) = collectFiles(mode)
        val outcome = engine.run(sessionId, if (usedDelta) mode else ManifestRequest.MODE_FULL, files, deadline)

        finishSession(sessionId, outcome, usedDelta)

        return CycleResult.Done(outcome, reached.endpoint)
    }

    private suspend fun journalDiscovery(discovered: List<ServerEndpoint>) {
        RoomJournal(container.database.log()).record(
            JournalLevel.INFO, "mdns_found",
            "Znaleziono serwer w sieci lokalnej: ${discovered.joinToString { it.toString() }}",
        )

        // Zapisujemy od razu: przy następnym cyklu trasa będzie już w zbiorze
        // kandydatów i wykrywanie nie będzie potrzebne.
        container.database.endpoints().upsert(
            discovered.map { EndpointEntity(it.host, it.port, it.kind.name) }
        )
    }

    // --- trasy ---

    private suspend fun loadCandidates(server: PairedServerEntity): List<ServerEndpoint> {
        val stored = container.database.endpoints().all().mapNotNull { entity ->
            val kind = runCatching { EndpointKind.valueOf(entity.kind) }.getOrDefault(EndpointKind.MANUAL)
            ServerEndpoint(entity.host, entity.port, kind)
        }

        Log.d(TAG, "Kandydatów na trasę: ${stored.size} (serwer ${server.label})")
        return stored
    }

    /**
     * Zapisuje zwycięską trasę i dopisuje adresy, które serwer sam o sobie zgłosił.
     *
     * To jest mechanizm z §4.4: jedno udane połączenie dowolną drogą odświeża wszystkie
     * pozostałe, więc zmiana routera w domu nie wymaga żadnej interakcji użytkownika.
     */
    private suspend fun rememberRoutes(reached: RaceOutcome.Reached) {
        val dao = container.database.endpoints()

        dao.markReached(
            reached.endpoint.host, reached.endpoint.port,
            at = System.currentTimeMillis(), rtt = reached.elapsedMillis,
        )

        val advertised = reached.ping.endpoints.mapNotNull { raw ->
            ServerEndpoint.parse(raw, EndpointKind.LAN)
        }

        if (advertised.isNotEmpty()) {
            dao.upsert(advertised.map { EndpointEntity(it.host, it.port, it.kind.name) })
        }
    }

    // --- skan ---

    /** Zwraca pliki i informację, czy udało się użyć szybkiej ścieżki. */
    private suspend fun collectFiles(mode: String): Pair<Sequence<ScannedFile>, Boolean> {
        val exclusions = ExclusionRules(
            userPrefixes = container.database.exclusions().active().map { it.pathPrefix }
        )
        val scanState = container.database.scanState().get(VOLUME)

        if (mode == ManifestRequest.MODE_DELTA && scanState != null && scanState.lastFullScanAt != null) {
            val media = MediaStoreScanner(container.context, exclusions)
            val changed = media.changedSince(scanState.lastGeneration)

            if (changed != null) {
                container.database.scanState().save(
                    scanState.copy(lastGeneration = media.currentGeneration())
                )
                return changed.asSequence() to true
            }
            // null oznacza cofnięty licznik, czyli przebudowany indeks MediaStore —
            // różnica byłaby niepełna, więc schodzimy do pełnego obchodu drzewa.
        }

        val root = File(pl.wspa.filum.scan.Environment.externalStorageRootPath())
        val generation = runCatching { MediaStoreScanner(container.context).currentGeneration() }.getOrDefault(0)

        container.database.scanState().save(
            ScanStateEntity(VOLUME, generation, lastFullScanAt = System.currentTimeMillis())
        )

        return FilesystemScanner(root, exclusions).walk() to false
    }

    private suspend fun finishSession(sessionId: String, outcome: SyncOutcome, usedDelta: Boolean) {
        val session = container.database.sessions().get(sessionId) ?: return

        val (files, bytes, result) = when (outcome) {
            is SyncOutcome.Completed -> Triple(outcome.filesSent, outcome.bytesSent, "completed")
            is SyncOutcome.Interrupted -> Triple(outcome.filesSent, outcome.bytesSent, "interrupted:${outcome.reason.name.lowercase()}")
            is SyncOutcome.Failed -> Triple(0, 0L, "failed:${outcome.reason}")
        }

        container.database.sessions().save(
            session.copy(
                endedAt = System.currentTimeMillis(),
                filesDone = files,
                bytesSent = bytes,
                result = if (usedDelta) result else "$result (pełny skan)",
            )
        )
    }

    private companion object {
        const val TAG = "FilumSync"
        const val VOLUME = "external_primary"
    }
}
