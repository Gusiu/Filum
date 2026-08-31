package pl.wspa.filum.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modele wymiany z serwerem. Lustro `Filum.Protocol.Dtos` po stronie .NET —
 * nazwy pól są normatywne i opisane w docs/PROTOCOL.md.
 */

/** Nagłówki i stałe protokołu. Jedno miejsce, żeby literały nie rozjechały się po kodzie. */
object FilumHeaders {
    const val PATH = "X-Filum-Path"
    const val SIZE = "X-Filum-Size"
    const val MTIME = "X-Filum-Mtime"
    const val OFFSET = "X-Filum-Offset"
    const val DIGEST = "X-Filum-Digest"
}

object Protocol {
    const val VERSION = 1
    const val PREFIX = "/api/v1"
}

// --- parowanie (§3.3) ---

/**
 * Ładunek zakodowany w kodzie QR. Nazwy pól są jednoliterowe, żeby kod QR pozostał gęsty
 * i dał się zeskanować bez celowania.
 */
@Serializable
data class QrPayload(
    @SerialName("n") val name: String,
    @SerialName("k") val key: String,
    @SerialName("t") val token: String,
    @SerialName("p") val port: Int,
    @SerialName("a") val addresses: List<String> = emptyList(),
    @SerialName("s") val service: String = "",
)

@Serializable
data class PairRequest(
    @SerialName("cert") val cert: String,
    @SerialName("label") val label: String,
)

@Serializable
data class PairResponse(
    @SerialName("deviceKey") val deviceKey: String,
    @SerialName("serverInstanceId") val serverInstanceId: String,
    @SerialName("protocol") val protocol: Int,
)

// --- GET /ping (§5.1) ---

@Serializable
data class ServerInfo(
    @SerialName("name") val name: String,
    @SerialName("version") val version: String,
    @SerialName("instanceId") val instanceId: String,
)

@Serializable
data class StorageInfo(
    @SerialName("freeBytes") val freeBytes: Long,
    @SerialName("totalBytes") val totalBytes: Long,
)

@Serializable
data class DeviceInfo(
    @SerialName("label") val label: String,
    @SerialName("fileCount") val fileCount: Long,
    @SerialName("bytesStored") val bytesStored: Long,
)

@Serializable
data class ProtocolLimits(
    @SerialName("manifestBatch") val manifestBatch: Int = 2000,
    @SerialName("smallFileThreshold") val smallFileThreshold: Int = 262_144,
    @SerialName("bundleMaxBytes") val bundleMaxBytes: Int = 8_388_608,
    @SerialName("bundleMaxCount") val bundleMaxCount: Int = 256,
    @SerialName("maxPathBytes") val maxPathBytes: Int = 4096,
)

@Serializable
data class PingResponse(
    @SerialName("protocol") val protocol: Int,
    @SerialName("server") val server: ServerInfo,
    @SerialName("endpoints") val endpoints: List<String> = emptyList(),
    @SerialName("storage") val storage: StorageInfo,
    @SerialName("device") val device: DeviceInfo,
    @SerialName("limits") val limits: ProtocolLimits = ProtocolLimits(),
    @SerialName("time") val time: String = "",
)

// --- POST /manifest (§5.2) ---

/**
 * Pozycja manifestu. Jednoliterowe nazwy nie są tu ozdobnikiem: przy 100 tys. plików
 * i partiach po 2000 narzut samych nazw pól w JSON przestaje być pomijalny.
 */
/**
 * Polityka wykrywania zmian (ADR-016).
 *
 * Mieszka w protokole, a nie w silniku, bo rozstrzyga o kształcie manifestu: w trybie
 * dokładnym pozycja niesie skrót, w szybkim nie. Ustawienie użytkownika i pole na
 * drucie muszą znaczyć to samo, więc jest to jeden typ, a nie dwa zsynchronizowane.
 *
 * Wybór należy do użytkownika, bo oba tryby są uzasadnione i różnią się ceną, nie
 * poprawnością. Koszt trybu dokładnego zależy od wielkości biblioteki i szybkości
 * pamięci urządzenia — czyli od rzeczy, których aplikacja nie zna z góry.
 */
enum class IntegrityPolicy {

    /**
     * Tożsamość z pary (rozmiar, czas modyfikacji) — ADR-002. Skan nie czyta zawartości
     * plików, więc jest szybki, ale podmiana zachowująca metadane zostaje niezauważona.
     */
    FAST,

    /**
     * Skrót każdego pliku przy każdym skanie, zestawiany z rejestrem serwera. Wykrywa
     * podmianę zachowującą metadane; kosztuje odczyt całej biblioteki w każdym cyklu.
     */
    THOROUGH,
}

@Serializable
data class ManifestEntry(
    @SerialName("p") val path: String,
    @SerialName("s") val size: Long,
    @SerialName("m") val mtime: Long,
    /**
     * Skrót zawartości — tylko w trybie dokładnym (§5.2, ADR-016). W trybie szybkim
     * pole nie jest wysyłane, bo jego policzenie wymaga odczytania każdego pliku.
     */
    @SerialName("d") val digest: String? = null,
)

@Serializable
data class ManifestRequest(
    @SerialName("session") val session: String,
    @SerialName("mode") val mode: String,
    @SerialName("batch") val batch: Int,
    @SerialName("final") val final: Boolean,
    @SerialName("files") val files: List<ManifestEntry>,
) {
    companion object {
        /** Sesja obejmuje komplet plików urządzenia; tylko wtedy serwer liczy sieroty. */
        const val MODE_FULL = "full"

        /** Przyrost od poprzedniego skanu. */
        const val MODE_DELTA = "delta"
    }
}

@Serializable
data class NeededFile(
    @SerialName("p") val path: String,
    @SerialName("offset") val offset: Long,
    @SerialName("versioned") val versioned: Boolean? = null,
)

/**
 * Ścieżka odrzucona już na etapie manifestu. Dzięki temu klient oznacza plik jako trwale
 * nieudany bez marnowania osobnego żądania PUT tylko po to, żeby dostać 422.
 */
@Serializable
data class RejectedFile(
    @SerialName("p") val path: String,
    @SerialName("error") val error: String,
)

@Serializable
data class ManifestResponse(
    @SerialName("have") val have: Long,
    @SerialName("needed") val needed: List<NeededFile> = emptyList(),
    @SerialName("rejected") val rejected: List<RejectedFile> = emptyList(),
    /**
     * Ścieżki, dla których skrót nie zgadza się z zapisanym w rejestrze serwera mimo
     * niezmienionych metadanych. Serwer wpisuje je również do `needed`, więc kopia
     * zostanie uzupełniona; ta lista istnieje po to, żeby dało się to **odnotować**.
     */
    @SerialName("divergent") val divergent: List<String> = emptyList(),
)

// --- PUT /file (§5.3) ---

@Serializable
data class FileChunkResponse(
    @SerialName("received") val received: Long,
    @SerialName("offset") val offset: Long,
    @SerialName("complete") val complete: Boolean,
    @SerialName("versioned") val versioned: String? = null,
)

// --- POST /bundle (§5.4) ---

@Serializable
data class BundleFileResult(
    @SerialName("p") val path: String,
    @SerialName("ok") val ok: Boolean,
    @SerialName("error") val error: String? = null,
    @SerialName("versioned") val versioned: String? = null,
)

@Serializable
data class BundleResponse(
    @SerialName("results") val results: List<BundleFileResult> = emptyList(),
)

/** Typ zawartości paczki. Ramkowanie binarne, nie multipart (§5.4). */
object BundleContentType {
    const val VALUE = "application/vnd.filum.bundle"
}

// --- POST /verify (§5.5) ---

@Serializable
data class VerifyEntry(
    @SerialName("p") val path: String,
    @SerialName("d") val digest: String,
)

@Serializable
data class VerifyRequest(
    @SerialName("files") val files: List<VerifyEntry>,
)

@Serializable
data class VerifyResponse(
    @SerialName("ok") val ok: List<String> = emptyList(),
    @SerialName("mismatch") val mismatch: List<String> = emptyList(),
    @SerialName("missing") val missing: List<String> = emptyList(),
)

// --- błędy (§6) ---

@Serializable
data class ErrorResponse(
    @SerialName("error") val error: String,
    @SerialName("message") val message: String = "",
    @SerialName("expectedOffset") val expectedOffset: Long? = null,
)

/**
 * Rozróżnienie błędów przejściowych od trwałych. Bez niego plik odrzucony przez 422
 * wracałby do kolejki w nieskończoność, a serwer chwilowo zajęty zostałby porzucony
 * na zawsze — obie pomyłki są kosztowne.
 */
enum class FailureKind { TRANSIENT, PERMANENT }

object HttpStatus {
    const val OK = 200
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val CONFLICT = 409
    const val PAYLOAD_TOO_LARGE = 413
    const val UNPROCESSABLE = 422
    const val TOO_MANY_REQUESTS = 429
    const val SERVICE_UNAVAILABLE = 503
    const val INSUFFICIENT_STORAGE = 507

    fun classify(code: Int): FailureKind = when (code) {
        TOO_MANY_REQUESTS, SERVICE_UNAVAILABLE, INSUFFICIENT_STORAGE -> FailureKind.TRANSIENT
        in 500..599 -> FailureKind.TRANSIENT
        else -> FailureKind.PERMANENT
    }
}
