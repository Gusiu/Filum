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
 * A conformance test between the implementations: **the real Kotlin client against the
 * real C# server**, over real mTLS.
 *
 * Server and client are written independently and reconciled solely by docs/PROTOCOL.md.
 * The unit tests on each side check that each does what its author intended - only this
 * test checks that **the intentions agree**. It is the one place where a divergence in
 * header encoding, in mtime units or in the semantics of resuming would show.
 *
 * Run conditionally, because it needs a running server. The launching command sets the
 * environment variables; without them the test is skipped and does not break a normal build.
 *
 * The Android Keystore is not needed here - the identity is built from a JVM key pair
 * through the same [FixedKeyManager] production uses.
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
        return FilumApiClient(FilumHttpClients(tls).defaultRoute)
    }

    @Test
    fun `a full cycle against the C# server`(@TempDir dir: Path) = runBlocking {
        val root = dir.toFile()

        // --- pairing: no client certificate, trust from the key fingerprint (§3.3) ---

        val paired = api(withIdentity = false).pair(
            endpoint = endpoint,
            token = pairingToken,
            certificateDer = clientCertificate.certificate.encoded,
            label = "Test client",
        )
        // The result goes into every message: a conformance test that says only "wrong
        // type" leaves you guessing which side diverged - and that is the one piece of
        // information you come here for.
        val pairResponse = assertInstanceOf(ApiResult.Ok::class.java, paired, paired.toString())
        println("paired, deviceKey=${(pairResponse.value as pl.wspa.filum.protocol.PairResponse).deviceKey}")

        val client = api(withIdentity = true)

        // --- ping: from here on every resource requires mTLS ---

        val ping = client.ping(endpoint)
        val pingOk = assertInstanceOf(ApiResult.Ok::class.java, ping, ping.toString())
        val limits = (pingOk.value as pl.wspa.filum.protocol.PingResponse).limits
        println("server answered, manifestBatch=${limits.manifestBatch}")

        // --- preparing files, including ones that break the NTFS rules ---

        write(root, "DCIM/Camera/IMG_0001.JPG", "photo content")
        write(root, "Documents/zdjęcie-łąka.txt", "non-ascii name")
        write(root, "Movies/large.bin", "x".repeat(200_000))

        val scanned = FilesystemScanner(
            root, ExclusionRules(includeDefaults = false), nowSeconds = { Long.MAX_VALUE / 2 },
        ).walk().toList().toMutableList()
        assertEquals(3, scanned.size, "the scanner should find all three files on disk")

        // A name with a colon is entirely ordinary on Android and forbidden under NTFS -
        // the main reason the PATH-RULES rules exist. The test runs on Windows, though,
        // where such a file cannot be created, so we supply that path as the canonical one
        // over a safely named file. The server still receives exactly what it would get from
        // a phone, so the sanitisation really is exercised.
        val withColon = File(root, "Documents/report-v2.txt").apply {
            writeText("a colon in the name")
            setLastModified(1_756_400_000_000)
        }
        scanned += pl.wspa.filum.scan.ScannedFile(
            canonicalPath = "Documents/report:v2.txt",
            absolutePath = withColon.absolutePath,
            size = withColon.length(),
            mtimeSeconds = 1_756_400_000,
        )

        // --- a full engine cycle ---

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

        val completed = assertInstanceOf(SyncOutcome.Completed::class.java, outcome, outcome.toString())
        println("sent ${completed.filesSent} files, ${completed.bytesSent} B")
        assertEquals(4, completed.filesSent)

        // --- second pass: the server has to recognise it already holds everything ---

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
        val manifest = assertInstanceOf(ApiResult.Ok::class.java, second, second.toString())
        val response = manifest.value as pl.wspa.filum.protocol.ManifestResponse

        // This is the crux: the server recognises files by the (path, size, mtime) triple
        // the client computed. A divergence in time units or in the normalisation
        // of names would surface right here - as a repeated request for the same files.
        assertEquals(4, response.have, "the server should hold everything after the first cycle")
        assertTrue(response.needed.isEmpty(), "nothing should be needed: ${response.needed}")
        assertTrue(response.rejected.isEmpty(), "no path should have been rejected")

        println("journal: ${journal.codes()}")
    }

    private fun write(root: File, path: String, content: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.setLastModified(1_756_400_000_000)
    }
}
