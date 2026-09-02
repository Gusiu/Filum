package pl.wspa.filum.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.source
import pl.wspa.filum.protocol.BundleResponse
import pl.wspa.filum.protocol.ErrorResponse
import pl.wspa.filum.protocol.FileChunkResponse
import pl.wspa.filum.protocol.FilumHeaders
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.protocol.ManifestResponse
import pl.wspa.filum.protocol.PairRequest
import pl.wspa.filum.protocol.PairResponse
import pl.wspa.filum.protocol.PingResponse
import pl.wspa.filum.protocol.Protocol
import pl.wspa.filum.protocol.VerifyEntry
import pl.wspa.filum.protocol.VerifyRequest
import pl.wspa.filum.protocol.VerifyResponse
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * The protocol's resources wrapped in typed calls (§5 of PROTOCOL.md).
 *
 * The client knows no "server address" — every call is given a specific route, because the
 * same instance may talk over the local network during one session and over the overlay
 * after a network change (ADR-006).
 */
class FilumApiClient(
    private val http: OkHttpClient,
    private val json: Json = defaultJson,
) {

    suspend fun pair(
        endpoint: ServerEndpoint,
        token: String,
        certificateDer: ByteArray,
        label: String,
    ): ApiResult<PairResponse> {
        val body = json.encodeToString(
            PairRequest(Base64.getEncoder().encodeToString(certificateDer), label)
        )

        val request = Request.Builder()
            .url(endpoint.url("/pair"))
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return execute(request) { json.decodeFromString<PairResponse>(it) }
    }

    suspend fun ping(endpoint: ServerEndpoint): ApiResult<PingResponse> {
        val request = Request.Builder().url(endpoint.url("/ping")).get().build()
        return execute(request) { json.decodeFromString<PingResponse>(it) }
    }

    suspend fun manifest(
        endpoint: ServerEndpoint,
        manifest: ManifestRequest,
    ): ApiResult<ManifestResponse> {
        val request = Request.Builder()
            .url(endpoint.url("/manifest"))
            .post(json.encodeToString(manifest).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return execute(request) { json.decodeFromString<ManifestResponse>(it) }
    }

    /**
     * Sends a chunk of a file. The body is streamed straight from disk — loading a
     * four-gigabyte recording into memory would kill the process on the first video.
     *
     * @param digest the digest of the whole file, sent with every chunk including on resume,
     *   because the server requires one on every request (§9.1, ADR-016).
     */
    suspend fun putFile(
        endpoint: ServerEndpoint,
        canonicalPath: String,
        file: File,
        size: Long,
        mtime: Long,
        offset: Long,
        length: Long,
        digest: String? = null,
    ): ApiResult<FileChunkResponse> {
        val builder = Request.Builder()
            .url(endpoint.url("/file"))
            .header(FilumHeaders.PATH, encodePath(canonicalPath))
            .header(FilumHeaders.SIZE, size.toString())
            .header(FilumHeaders.MTIME, mtime.toString())
            .header(FilumHeaders.OFFSET, offset.toString())
            .put(FileChunkBody(file, offset, length))

        if (digest != null) builder.header(FilumHeaders.DIGEST, digest)

        return execute(builder.build()) { json.decodeFromString<FileChunkResponse>(it) }
    }

    /**
     * Sends a bundle of small files (§5.4).
     *
     * The request may succeed **partially**: the response carries a result per file, because
     * one rejected path does not invalidate the other hundred.
     */
    suspend fun putBundle(
        endpoint: ServerEndpoint,
        entries: List<BundleEntry>,
    ): ApiResult<BundleResponse> {
        val request = Request.Builder()
            .url(endpoint.url("/bundle"))
            .post(BundleBody(entries))
            .build()

        return execute(request) { json.decodeFromString<BundleResponse>(it) }
    }

    /**
     * Asks the server to check the digests of its own copies (§5.5).
     *
     * The operation is expensive on the server — it hashes files from disk — so only a
     * deliberate decision by the user invokes it, never an automatic cycle.
     */
    suspend fun verify(
        endpoint: ServerEndpoint,
        entries: List<VerifyEntry>,
    ): ApiResult<VerifyResponse> {
        val request = Request.Builder()
            .url(endpoint.url("/verify"))
            .post(json.encodeToString(VerifyRequest(entries)).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return execute(request) { json.decodeFromString<VerifyResponse>(it) }
    }

    private fun ServerEndpoint.url(resource: String): String = "$baseUrl${Protocol.PREFIX}$resource"

    private suspend fun <T> execute(request: Request, parse: (String) -> T): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response -> interpret(response, parse) }
            } catch (e: IOException) {
                ApiResult.Unreachable(e)
            }
        }

    private fun <T> interpret(response: Response, parse: (String) -> T): ApiResult<T> {
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            return ApiResult.failure(response.code, decodeError(body))
        }

        return try {
            ApiResult.Ok(parse(body))
        } catch (e: Exception) {
            // A valid response code but an unreadable body — most often this means
            // there is no Filum at the other end at all, but somebody else's service.
            ApiResult.Unreachable(IOException("The server response cannot be read: ${e.message}", e))
        }
    }

    private fun decodeError(body: String): ErrorResponse? = try {
        if (body.isBlank()) null else json.decodeFromString<ErrorResponse>(body)
    } catch (e: Exception) {
        null
    }

    /** The canonical path in the header: base64url without padding (§5.3). */
    private fun encodePath(canonicalPath: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(canonicalPath.toByteArray(Charsets.UTF_8))

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false

            // An explicit `null` in a field with a default is to mean the same as the field
            // being absent. Without this, the response `{"divergent":null}` wrecked the
            // **whole** manifest parse over an optional, empty field — one side omitted the
            // field, the other wrote null, and that was enough to stop the sync.
            // Unknown keys are already ignored; there is no reason for a known key holding
            // null to be treated more harshly.
            coerceInputValues = true
        }
    }
}

/**
 * A chunk of a file as a request body, streamed from a given offset.
 *
 * We do not build on the ready-made `asRequestBody`, because that always sends the whole
 * file — and resuming is precisely about sending only the missing piece.
 */
internal class FileChunkBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
) : RequestBody() {

    override fun contentType() = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        file.inputStream().use { stream ->
            var skipped = 0L
            while (skipped < offset) {
                val step = stream.skip(offset - skipped)
                if (step <= 0) throw IOException("Could not seek the file to $offset: ${file.name}")
                skipped += step
            }

            stream.source().buffer().use { source ->
                var remaining = length
                while (remaining > 0) {
                    val written = source.read(sink.buffer, minOf(remaining, SEGMENT))
                    if (written == -1L) throw IOException("The file shrank during upload: ${file.name}")
                    remaining -= written
                    sink.flush()
                }
            }
        }
    }

    private companion object {
        const val SEGMENT = 64L * 1024
    }
}
