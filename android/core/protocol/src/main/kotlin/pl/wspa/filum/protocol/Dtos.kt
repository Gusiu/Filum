package pl.wspa.filum.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Modele wymiany z serwerem. Lustro `Filum.Protocol.Dtos` po stronie .NET -
 * the field names are normative and described in docs/PROTOCOL.md.
 */

/** Protocol headers and constants. One place, so the literals cannot drift apart. */
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
 * The payload encoded in the QR code. Single-letter field names keep the code dense and
 * scannable without careful aiming.
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
 * A manifest entry. The single-letter names are not decoration: across a hundred thousand
 * files in batches of two thousand, the cost of the field names alone stops being
 * negligible.
 */
/**
 * Polityka wykrywania zmian (ADR-016).
 *
 * It lives in the protocol rather than the engine, because it decides the manifest's
 * shape: in thorough mode an entry carries a digest, in fast mode it does not. The user's
 * setting and the field on the wire have to mean the same thing, so this is one type
 * rather than two kept in step.
 *
 * The choice belongs to the user, because both modes are justified and differ in cost
 * rather than correctness. What thorough mode costs depends on the library's size and the
 * device's storage speed - things the application cannot know in advance.
 */
enum class IntegrityPolicy {

    /**
     * Identity from the (size, modification time) pair - ADR-002. The scan does not read file
     * contents, so it is fast, but a metadata-preserving substitution goes unnoticed.
     */
    FAST,

    /**
     * A digest of every file on every scan, compared against the server's ledger. It detects a
     * metadata-preserving substitution; it costs reading the whole library every cycle.
     */
    THOROUGH,
}

@Serializable
data class ManifestEntry(
    @SerialName("p") val path: String,
    @SerialName("s") val size: Long,
    @SerialName("m") val mtime: Long,
    /**
     * The content digest - thorough mode only (§5.2, ADR-016). In fast mode the field is not
     * sent, because computing it requires reading every file.
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
        /** The session covers the device's complete file set; only then does the server compute orphans. */
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
 * A path rejected at the manifest stage. It lets the client mark a file as permanently
 * failed without wasting a separate PUT request just to receive a 422.
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
     * Paths whose digest disagrees with the one recorded in the server's ledger, despite
     * unchanged metadata. The server also places them in `needed`, so the copy will be
     * brought up to date; this list exists so the client can **record** the fact.
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

/** The bundle content type. Binary framing, not multipart (§5.4). */
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

// --- errors (§6) ---

@Serializable
data class ErrorResponse(
    @SerialName("error") val error: String,
    @SerialName("message") val message: String = "",
    @SerialName("expectedOffset") val expectedOffset: Long? = null,
)

/**
 * Telling transient errors from permanent ones. Without it, a file rejected with 422 would
 * return to the queue forever, while a momentarily busy server would be abandoned for
 * good - both mistakes are expensive.
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
