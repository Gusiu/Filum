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
 * Zasoby protokołu opakowane w wywołania typowane (§5 dokumentu PROTOCOL.md).
 *
 * Klient nie zna „adresu serwera" — każde wywołanie dostaje konkretną trasę, bo
 * ta sama instancja może w ciągu jednej sesji rozmawiać przez sieć lokalną,
 * a po zmianie sieci przez nakładkową (ADR-006).
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
     * Wysyła fragment pliku. Ciało jest strumieniowane wprost z dysku — wczytanie
     * czterogigabajtowego nagrania do pamięci zabiłoby proces przy pierwszym filmie.
     *
     * @param digest skrót całego pliku; podawany tylko gdy plik idzie w jednym
     *   przebiegu, bo przy wznowieniu klient musiałby odczytać go od początku (§9).
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
     * Wysyła paczkę małych plików (§5.4).
     *
     * Żądanie może się powieść **częściowo**: odpowiedź niesie wynik dla każdego pliku
     * z osobna, bo jedna odrzucona ścieżka nie unieważnia pozostałych stu.
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
     * Prosi serwer o sprawdzenie skrótów swoich kopii (§5.5).
     *
     * Operacja jest po stronie serwera kosztowna — liczy skróty plików z dysku —
     * więc wywołuje ją wyłącznie świadoma decyzja użytkownika, nigdy cykl automatyczny.
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
            // Poprawny kod odpowiedzi, ale ciało nie do odczytania — najczęściej
            // oznacza, że po drugiej stronie nie ma wcale Filum, tylko cudza usługa.
            ApiResult.Unreachable(IOException("Odpowiedź serwera nie da się odczytać: ${e.message}", e))
        }
    }

    private fun decodeError(body: String): ErrorResponse? = try {
        if (body.isBlank()) null else json.decodeFromString<ErrorResponse>(body)
    } catch (e: Exception) {
        null
    }

    /** Ścieżka kanoniczna w nagłówku: base64url bez dopełnienia (§5.3). */
    private fun encodePath(canonicalPath: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(canonicalPath.toByteArray(Charsets.UTF_8))

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/**
 * Fragment pliku jako ciało żądania, czytany strumieniowo od zadanego przesunięcia.
 *
 * Nie dziedziczymy po gotowym `asRequestBody`, bo ono zawsze wysyła cały plik —
 * a wznawianie polega właśnie na wysłaniu wyłącznie brakującego kawałka.
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
                if (step <= 0) throw IOException("Nie udało się przewinąć pliku do $offset: ${file.name}")
                skipped += step
            }

            stream.source().buffer().use { source ->
                var remaining = length
                while (remaining > 0) {
                    val written = source.read(sink.buffer, minOf(remaining, SEGMENT))
                    if (written == -1L) throw IOException("Plik skrócił się w trakcie wysyłki: ${file.name}")
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
