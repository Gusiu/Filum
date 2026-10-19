package pl.wspa.filum.app

import android.app.Application
import android.content.Context
import pl.wspa.filum.crypto.DeviceIdentity
import pl.wspa.filum.crypto.KeySecurity
import pl.wspa.filum.crypto.FilumTls
import pl.wspa.filum.data.EndpointEntity
import pl.wspa.filum.data.FilumDatabase
import pl.wspa.filum.data.PairedServerEntity
import pl.wspa.filum.data.SettingsEntity
import pl.wspa.filum.net.AndroidLocalNetworkBinder
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.net.EndpointKind
import pl.wspa.filum.net.EndpointRacer
import pl.wspa.filum.net.FilumApiClient
import pl.wspa.filum.net.FilumHttpClients
import pl.wspa.filum.net.Reachability
import pl.wspa.filum.net.ServerEndpoint
import pl.wspa.filum.protocol.PairingCode
import pl.wspa.filum.protocol.QrPayload
import pl.wspa.filum.sync.JournalLevel

/**
 * The application's dependencies, wired by hand.
 *
 * Deliberately without an injection library: the graph has a dozen or so nodes and is
 * visible in its entirety in this file. For a thesis, legibility beats saving a few lines,
 * and every extra layer is one more thing to explain at the defence.
 */
class AppContainer(val context: Context) {

    val database: FilumDatabase by lazy { FilumDatabase.open(context) }

    val networkBinder: AndroidLocalNetworkBinder by lazy { AndroidLocalNetworkBinder(context) }

    val queue: RoomSyncQueue by lazy { RoomSyncQueue(database.files()) }

    val journal: RoomJournal by lazy { RoomJournal(database.log()) }

    val pairing: PairingController by lazy { PairingController(this) }

    val syncRunner: SyncRunner by lazy { SyncRunner(this) }

    val verification: VerificationRunner by lazy { VerificationRunner(this) }

    /** The settings, with defaults on first run. */
    suspend fun settings(): SettingsEntity =
        database.settings().get() ?: SettingsEntity().also { database.settings().save(it) }
}

class FilumApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

sealed interface PairingResult {
    data class Paired(val label: String, val endpoint: ServerEndpoint) : PairingResult
    data class Rejected(val message: String) : PairingResult
}

/**
 * The pairing exchange (protocol §3.3).
 *
 * The phone generates its own key pair in the Android Keystore, presents the certificate
 * to the server and remembers the server's key fingerprint from the QR code. From then on
 * the address stops being an identity - only the key counts (ADR-006).
 */
class PairingController(private val container: AppContainer) {

    suspend fun pair(rawCode: String, deviceLabel: String): PairingResult {
        val payload = when (val parsed = PairingCode.parse(rawCode)) {
            is PairingCode.Result.Ok -> parsed.payload
            is PairingCode.Result.Invalid -> return PairingResult.Rejected(parsed.reason)
        }

        val identity = DeviceIdentity.loadOrCreate()

        // During pairing we have no right to present a client certificate yet - /pair is
        // the one resource reachable without mTLS. Trust in the server rests solely on the
        // key fingerprint from the QR code.
        val bootstrapTls = FilumTls.bundle(payload.key, keyManagers = null)
        val clients = FilumHttpClients(bootstrapTls, container.networkBinder)

        val candidates = payload.addresses.mapNotNull {
            ServerEndpoint.parse(it, EndpointKind.MANUAL, payload.port)?.classified()
        }
        if (candidates.isEmpty()) {
            return PairingResult.Rejected("The pairing code contains no server address.")
        }

        // The code lists every interface of the computer, and asking them one after another
        // made a single unreachable address cost a whole timeout. The routes are raced first;
        // the single-use token then goes to the one that answered.
        val reach = EndpointRacer(
            probe = { endpoint -> FilumApiClient(clients.forProbe(endpoint)).ping(endpoint) }
        ).firstAnswering(clients.routesFor(candidates))

        val answered = when (reach) {
            is Reachability.Answered -> reach
            is Reachability.Silent -> return PairingResult.Rejected(
                "No address from the pairing code answered. ${reach.reasons().joinToString("; ")}"
            )
        }

        val endpoint = answered.endpoint
        val api = FilumApiClient(clients.forEndpoint(endpoint))

        return when (val result = api.pair(endpoint, payload.token, identity.certificateDer, deviceLabel)) {
            is ApiResult.Ok -> {
                persist(payload, result.value.serverInstanceId, identity, candidates)

                // The key's security level goes into the journal at pairing time:
                // this is the only moment it is created, and when diagnosing it helps to
                // know whether the key sits in a separate chip or only in a TEE.
                RoomJournal(container.database.log()).record(
                    JournalLevel.INFO, "paired",
                    "Paired with ${payload.name} over $endpoint in ${answered.elapsedMillis} ms. " +
                        "Device key: ${describe(identity.security)}.",
                )

                PairingResult.Paired(payload.name, endpoint)
            }

            // The token is single-use, so a refusal is final - another address would only
            // be refused again. We stop with a legible reason.
            is ApiResult.Failure -> PairingResult.Rejected(result.message)

            is ApiResult.Unreachable -> PairingResult.Rejected("$endpoint - ${result.message}")
        }
    }

    private suspend fun persist(
        payload: QrPayload,
        serverInstanceId: String,
        identity: DeviceIdentity,
        candidates: List<ServerEndpoint>,
    ) {
        container.database.pairedServers().save(
            PairedServerEntity(
                label = payload.name,
                serverPin = payload.key,
                keyAlias = identity.alias,
                serverInstanceId = serverInstanceId,
                pairedAt = System.currentTimeMillis(),
            )
        )

        container.database.endpoints().upsert(
            candidates.map { EndpointEntity(it.host, it.port, it.kind.name) }
        )
    }

    private fun describe(security: KeySecurity): String = when (security) {
        KeySecurity.STRONGBOX -> "a separate secure element (StrongBox)"
        KeySecurity.TRUSTED_ENVIRONMENT -> "the processor's trusted environment (TEE)"
        KeySecurity.SOFTWARE -> "process memory - no hardware backing"
        KeySecurity.UNKNOWN -> "an undetermined protection level"
    }

    suspend fun unpair() {
        container.database.pairedServers().clear()
        container.database.endpoints().clear()
        container.database.files().clear()
        container.database.scanState().clear()
        DeviceIdentity.delete()
    }
}
