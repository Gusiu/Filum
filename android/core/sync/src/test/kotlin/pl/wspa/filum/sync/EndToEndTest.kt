package pl.wspa.filum.sync

import kotlinx.coroutines.runBlocking
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import pl.wspa.filum.crypto.FilumTls
import pl.wspa.filum.crypto.FixedKeyManager
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.net.EndpointKind
import pl.wspa.filum.net.FilumApiClient
import pl.wspa.filum.net.FilumHttpClients
import pl.wspa.filum.net.ServerEndpoint
import pl.wspa.filum.protocol.ManifestEntry
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.scan.ExclusionRules
import pl.wspa.filum.scan.FilesystemScanner
import java.io.File
import java.nio.file.Path
import javax.net.ssl.KeyManager

/**
 * Test zgodności między implementacjami: **prawdziwy klient kotlinowy przeciwko
 * prawdziwemu serwerowi w C#**, po prawdziwym mTLS.
 *
 * Serwer i klient są pisane niezależnie i pogodzone wyłącznie przez docs/PROTOCOL.md.
 * Testy jednostkowe po obu stronach sprawdzają, czy każda z nich robi to, co jej autor
 * zamierzył — dopiero ten test sprawdza, czy **zamierzenia się pokrywają**. To jest
 * jedyne miejsce, w którym wychodzi rozjazd w kodowaniu nagłówka, w jednostkach mtime
 * albo w semantyce wznawiania.
 *
 * Uruchamiany warunkowo, bo wymaga działającego serwera. Skrypt uruchamiający ustawia
 * zmienne środowiskowe; bez nich test jest pomijany i nie psuje zwykłej kompilacji.
 *
 * Android Keystore nie jest tu potrzebny — tożsamość budujemy z pary kluczy JVM przez
 * ten sam [FixedKeyManager], którego używa produkcja.
 */
@EnabledIfEnvironmentVariable(named = "FILUM_HOST", matches = ".+")
class EndToEndTest {

    private val host = System.getenv("FILUM_HOST") ?: "127.0.0.1"
    private val port = System.getenv("FILUM_PORT")?.toIntOrNull() ?: 8765
    private val serverPin = System.getenv("FILUM_PIN").orEmpty()
    private val pairingToken = System.getenv("FILUM_TOKEN").orEmpty()

    private val endpoint get() = ServerEndpoint(host, port, EndpointKind.LAN)

    private val clientCertificate = HeldCertificate.Builder()
        .commonName("Filum Test Client")
        .build()

    private fun keyManagers(): Array<KeyManager> = arrayOf(
        FixedKeyManager("test", clientCertificate.certificate, clientCertificate.keyPair.private)
    )

    private fun api(withIdentity: Boolean): FilumApiClient {
        val tls = FilumTls.bundle(serverPin, if (withIdentity) keyManagers() else null)
        return FilumApiClient(FilumHttpClients(tls).forKind(EndpointKind.LAN))
    }

    @Test
    fun `pelny cykl przeciwko serwerowi w C#`(@TempDir dir: Path) = runBlocking {
        val root = dir.toFile()

        // --- parowanie: bez certyfikatu klienta, zaufanie z odcisku klucza (§3.3) ---

        val paired = api(withIdentity = false).pair(
            endpoint = endpoint,
            token = pairingToken,
            certificateDer = clientCertificate.certificate.encoded,
            label = "Klient testowy",
        )
        val pairResponse = assertInstanceOf(ApiResult.Ok::class.java, paired)
        println("sparowano, deviceKey=${(pairResponse.value as pl.wspa.filum.protocol.PairResponse).deviceKey}")

        val client = api(withIdentity = true)

        // --- ping: od teraz każdy zasób wymaga mTLS ---

        val ping = client.ping(endpoint)
        val pingOk = assertInstanceOf(ApiResult.Ok::class.java, ping)
        val limits = (pingOk.value as pl.wspa.filum.protocol.PingResponse).limits
        println("serwer odpowiedział, manifestBatch=${limits.manifestBatch}")

        // --- przygotowanie plików, w tym takich, które łamią reguły NTFS ---

        write(root, "DCIM/Camera/IMG_0001.JPG", "zawartosc zdjecia")
        write(root, "Documents/zdjęcie-łąka.txt", "polskie znaki")
        write(root, "Movies/duzy.bin", "x".repeat(200_000))

        val scanned = FilesystemScanner(
            root, ExclusionRules(includeDefaults = false), nowSeconds = { Long.MAX_VALUE / 2 },
        ).walk().toList().toMutableList()
        assertEquals(3, scanned.size, "skaner powinien znaleźć wszystkie trzy pliki z dysku")

        // Nazwa z dwukropkiem jest na Androidzie zupełnie zwyczajna, a pod NTFS zabroniona —
        // to główny powód istnienia reguł z PATH-RULES. Test biegnie jednak na Windows,
        // gdzie takiego pliku nie da się utworzyć, więc podajemy tę ścieżkę jako kanoniczną
        // nad plikiem o bezpiecznej nazwie. Serwer i tak dostaje dokładnie to, co dostałby
        // z telefonu, więc sanityzacja jest sprawdzona naprawdę.
        val zDwukropkiem = File(root, "Documents/raport-v2.txt").apply {
            writeText("dwukropek w nazwie")
            setLastModified(1_756_400_000_000)
        }
        scanned += pl.wspa.filum.scan.ScannedFile(
            canonicalPath = "Documents/raport:v2.txt",
            absolutePath = zDwukropkiem.absolutePath,
            size = zDwukropkiem.length(),
            mtimeSeconds = 1_756_400_000,
        )

        // --- pełny cykl silnika ---

        val queue = FakeQueue()
        val journal = FakeJournal()
        val engine = SyncEngine(
            transport = object : SyncTransport {
                override suspend fun manifest(request: ManifestRequest) = client.manifest(endpoint, request)

                override suspend fun putFile(
                    canonicalPath: String, file: File, size: Long, mtime: Long,
                    offset: Long, length: Long, digest: String?,
                ) = client.putFile(endpoint, canonicalPath, file, size, mtime, offset, length, digest)

                override suspend fun putBundle(entries: List<pl.wspa.filum.net.BundleEntry>) =
                    client.putBundle(endpoint, entries)
            },
            queue = queue,
            journal = journal,
            config = SyncConfig(manifestBatch = limits.manifestBatch, chunkBytes = 64 * 1024),
        )

        val outcome = engine.run("e2e-1", ManifestRequest.MODE_FULL, scanned.asSequence(), Deadline { false })

        val completed = assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        println("wysłano ${completed.filesSent} plików, ${completed.bytesSent} B")
        assertEquals(4, completed.filesSent)

        // --- drugi przebieg: serwer musi rozpoznać, że już wszystko ma ---

        val second = client.manifest(
            endpoint,
            ManifestRequest(
                session = "e2e-2",
                mode = ManifestRequest.MODE_FULL,
                batch = 0,
                final = true,
                files = scanned.map { ManifestEntry(it.canonicalPath, it.size, it.mtimeSeconds) },
            ),
        )
        val manifest = assertInstanceOf(ApiResult.Ok::class.java, second)
        val response = manifest.value as pl.wspa.filum.protocol.ManifestResponse

        // To jest sedno: serwer rozpoznaje pliki po trójce (ścieżka, rozmiar, mtime)
        // wyliczonej przez klienta. Rozjazd w jednostkach czasu albo w normalizacji
        // nazw ujawniłby się właśnie tutaj - jako ponowna prośba o te same pliki.
        assertEquals(4, response.have, "serwer powinien mieć komplet po pierwszym cyklu")
        assertTrue(response.needed.isEmpty(), "nic nie powinno być potrzebne: ${response.needed}")
        assertTrue(response.rejected.isEmpty(), "żadna ścieżka nie powinna zostać odrzucona")

        println("dziennik: ${journal.codes()}")
    }

    private fun write(root: File, path: String, content: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.setLastModified(1_756_400_000_000)
    }
}
