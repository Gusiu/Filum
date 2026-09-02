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
import pl.wspa.filum.net.FilumApiClient
import pl.wspa.filum.net.FilumHttpClients
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
 * the address stops being an identity — only the key counts (ADR-006).
 */
class PairingController(private val container: AppContainer) {

    suspend fun pair(rawCode: String, deviceLabel: String): PairingResult {
        val payload = when (val parsed = PairingCode.parse(rawCode)) {
            is PairingCode.Result.Ok -> parsed.payload
            is PairingCode.Result.Invalid -> return PairingResult.Rejected(parsed.reason)
        }

        val identity = DeviceIdentity.loadOrCreate()

        // During pairing we have no right to present a client certificate yet — /pair is
        // the one resource reachable without mTLS. Trust in the server rests solely on the
        // key fingerprint from the QR code.
        val bootstrapTls = FilumTls.bundle(payload.key, keyManagers = null)
        val api = FilumApiClient(FilumHttpClients(bootstrapTls, container.networkBinder).forKind(EndpointKind.MANUAL))

        val candidates = payload.addresses.mapNotNull {
            ServerEndpoint.parse(it, EndpointKind.MANUAL, payload.port)
        }
        if (candidates.isEmpty()) {
            return PairingResult.Rejected("The pairing code contains no server address.")
        }

        var lastProblem = "Could not connect to the server."

        for (endpoint in candidates) {
            when (val result = api.pair(endpoint, payload.token, identity.certificateDer, deviceLabel)) {
                is ApiResult.Ok -> {
                    persist(payload, result.value.serverInstanceId, identity, candidates)

                    // The key's security level goes into the journal at pairing time:
                    // this is the only moment it is created, and when diagnosing it helps to
                    // know whether the key sits in a separate chip or only in a TEE.
                    RoomJournal(container.database.log()).record(
                        JournalLevel.INFO, "paired",
                        "Paired with ${payload.name}. Device key: ${describe(identity.security)}.",
                    )

                    return PairingResult.Paired(payload.name, endpoint)
                }

                is ApiResult.Failure -> {
                    // The token is single-use: a failed attempt consumed it, so the remaining
                    // addresses have nothing to try. We stop with a legible reason.
                    return PairingResult.Rejected(result.message)
                }

                is ApiResult.Unreachable -> lastProblem = "$endpoint — ${result.message}"
            }
        }

        return PairingResult.Rejected(lastProblem)
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
            candidates.map { EndpointEntity(it.host, it.port, EndpointKind.LAN.name) }
        )
    }

    private fun describe(security: KeySecurity): String = when (security) {
        KeySecurity.STRONGBOX -> "osobny element bezpieczny (StrongBox)"
        KeySecurity.TRUSTED_ENVIRONMENT -> "the processor's trusted environment (TEE)"
        KeySecurity.SOFTWARE -> "process memory — no hardware backing"
        KeySecurity.UNKNOWN -> "nieustalony poziom zabezpieczenia"
    }

    suspend fun unpair() {
        container.database.pairedServers().clear()
        container.database.endpoints().clear()
        container.database.files().clear()
        container.database.scanState().clear()
        DeviceIdentity.delete()
    }
}
