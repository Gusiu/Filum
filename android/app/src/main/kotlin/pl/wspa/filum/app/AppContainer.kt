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
 * Zależności aplikacji, składane ręcznie.
 *
 * Świadomie bez biblioteki wstrzykiwania: graf ma kilkanaście węzłów i jest w całości
 * widoczny w tym pliku. Dla pracy dyplomowej czytelność jest ważniejsza niż oszczędność
 * kilku linii, a każda dodatkowa warstwa to kolejna rzecz do wyjaśnienia przy obronie.
 */
class AppContainer(val context: Context) {

    val database: FilumDatabase by lazy { FilumDatabase.open(context) }

    val networkBinder: AndroidLocalNetworkBinder by lazy { AndroidLocalNetworkBinder(context) }

    val queue: RoomSyncQueue by lazy { RoomSyncQueue(database.files()) }

    val journal: RoomJournal by lazy { RoomJournal(database.log()) }

    val pairing: PairingController by lazy { PairingController(this) }

    val syncRunner: SyncRunner by lazy { SyncRunner(this) }

    val verification: VerificationRunner by lazy { VerificationRunner(this) }

    /** Ustawienia z wartościami domyślnymi przy pierwszym uruchomieniu. */
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
 * Przebieg parowania (§3.3 protokołu).
 *
 * Telefon generuje własną parę kluczy w Android Keystore, przedstawia certyfikat
 * serwerowi i zapamiętuje odcisk klucza serwera z kodu QR. Od tej chwili adres
 * przestaje być tożsamością — liczy się wyłącznie klucz (ADR-006).
 */
class PairingController(private val container: AppContainer) {

    suspend fun pair(rawCode: String, deviceLabel: String): PairingResult {
        val payload = when (val parsed = PairingCode.parse(rawCode)) {
            is PairingCode.Result.Ok -> parsed.payload
            is PairingCode.Result.Invalid -> return PairingResult.Rejected(parsed.reason)
        }

        val identity = DeviceIdentity.loadOrCreate()

        // Podczas parowania nie mamy jeszcze prawa przedstawiać certyfikatu klienta —
        // /pair jest jedynym zasobem dostępnym bez mTLS. Zaufanie do serwera opiera się
        // wyłącznie na odcisku klucza z kodu QR.
        val bootstrapTls = FilumTls.bundle(payload.key, keyManagers = null)
        val api = FilumApiClient(FilumHttpClients(bootstrapTls, container.networkBinder).forKind(EndpointKind.MANUAL))

        val candidates = payload.addresses.mapNotNull {
            ServerEndpoint.parse(it, EndpointKind.MANUAL, payload.port)
        }
        if (candidates.isEmpty()) {
            return PairingResult.Rejected("Kod parowania nie zawiera adresu serwera.")
        }

        var lastProblem = "Nie udało się połączyć z serwerem."

        for (endpoint in candidates) {
            when (val result = api.pair(endpoint, payload.token, identity.certificateDer, deviceLabel)) {
                is ApiResult.Ok -> {
                    persist(payload, result.value.serverInstanceId, identity, candidates)

                    // Poziom zabezpieczenia klucza trafia do dziennika przy parowaniu:
                    // to jedyny moment, w którym powstaje, a przy diagnozowaniu warto
                    // wiedzieć, czy klucz siedzi w osobnym układzie, czy tylko w TEE.
                    RoomJournal(container.database.log()).record(
                        JournalLevel.INFO, "paired",
                        "Sparowano z ${payload.name}. Klucz urządzenia: ${describe(identity.security)}.",
                    )

                    return PairingResult.Paired(payload.name, endpoint)
                }

                is ApiResult.Failure -> {
                    // Token jest jednorazowy: nieudana próba go zużyła, więc dalsze
                    // adresy nie mają czego spróbować. Kończymy z czytelnym powodem.
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
        KeySecurity.TRUSTED_ENVIRONMENT -> "zaufane środowisko procesora (TEE)"
        KeySecurity.SOFTWARE -> "pamięć procesu — brak wsparcia sprzętowego"
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
