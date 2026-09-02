package pl.wspa.filum.app

import android.util.Log
import pl.wspa.filum.crypto.DeviceIdentity
import pl.wspa.filum.crypto.FilumTls
import pl.wspa.filum.data.EndpointEntity
import pl.wspa.filum.data.PairedServerEntity
import pl.wspa.filum.data.ScanStateEntity
import pl.wspa.filum.data.SettingsEntity
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
    data class NotPaired(val message: String = "The application is not paired with a server.") : CycleResult
    data class NoRoute(val failures: List<String>) : CycleResult
}

/**
 * One full cycle: find the server, scan, agree the manifest, upload.
 *
 * The order matters. The route is chosen **before** the scan, because a scan with nobody
 * to receive is wasted battery; and the ping response refreshes the route set on the way (§4.4).
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

        // Stored routes first — in the vast majority of cycles one of them answers and
        // there is no reason to pay for discovery.
        var race = racer.fastest(loadCandidates(server))

        if (race !is RaceOutcome.Reached) {
            // No stored route worked. This is the one situation in which the route set's
            // self-update (§4.4) has nothing to bootstrap from: the phone was away from home
            // across a router change and has no overlay network.
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
            "Connected over ${reached.endpoint} in ${reached.elapsedMillis} ms.",
        )

        // Default settings until the user changes anything — the first cycle after
        // po instalacji nie ma jeszcze wiersza w bazie.
        val settings = container.database.settings().get() ?: SettingsEntity()

        val sessionId = UUID.randomUUID().toString()
        container.database.sessions().start(
            SyncSessionEntity(sessionId, mode, startedAt = System.currentTimeMillis())
        )

        val engine = SyncEngine(
            transport = ApiSyncTransport(FilumApiClient(clients.forKind(reached.endpoint.kind)), reached.endpoint),
            queue = container.queue,
            journal = journal,
            // The limits come from the ping response; the client assumes none of its own (§5.1).
            // The integrity policy is the exception — that is the device user's decision, not
            // the server's, because it is their time and battery that pay for it.
            config = SyncConfig(
                manifestBatch = reached.ping.limits.manifestBatch,
                smallFileThreshold = reached.ping.limits.smallFileThreshold.toLong(),
                bundleMaxBytes = reached.ping.limits.bundleMaxBytes.toLong(),
                bundleMaxCount = reached.ping.limits.bundleMaxCount,
                integrityPolicy = settings.integrityPolicy,
            ),
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

        // Stored immediately: on the next cycle the route is already among the
        // candidates and discovery will not be needed.
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

        Log.d(TAG, "Route candidates: ${stored.size} (server ${server.label})")
        return stored
    }

    /**
     * Stores the winning route and adds the addresses the server reported about itself.
     *
     * This is the mechanism from §4.4: one successful connection by any road refreshes all the
     * others, so changing the home router needs no user interaction at all.
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

    /** Returns the files and whether the fast path could be used. */
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
            // null means the counter went backwards, i.e. the MediaStore index was rebuilt —
            // the delta would be partial, so we drop to a full tree walk.
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
                result = if (usedDelta) result else "$result (full scan)",
            )
        )
    }

    private companion object {
        const val TAG = "FilumSync"
        const val VOLUME = "external_primary"
    }
}
