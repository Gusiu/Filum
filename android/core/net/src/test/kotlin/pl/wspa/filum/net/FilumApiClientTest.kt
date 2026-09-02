package pl.wspa.filum.net

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.wspa.filum.crypto.FilumTls
import pl.wspa.filum.crypto.FixedKeyManager
import pl.wspa.filum.crypto.KeyPin
import pl.wspa.filum.protocol.FailureKind
import pl.wspa.filum.protocol.FilumHeaders
import pl.wspa.filum.protocol.ManifestEntry
import pl.wspa.filum.protocol.ManifestRequest
import java.io.File
import java.nio.file.Path
import java.util.Base64
import javax.net.ssl.KeyManager

/**
 * The resource client over **real TLS**. MockWebServer performs a genuine handshake, so the
 * test also covers key pinning and presenting the client certificate — precisely the things
 * an HTTP fake would not check.
 */
class FilumApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var serverCertificate: HeldCertificate
    private lateinit var clientCertificate: HeldCertificate

    private val serverPin: String get() = KeyPin.of(serverCertificate.certificate)

    @BeforeEach
    fun setUp() {
        serverCertificate = HeldCertificate.Builder().commonName("Filum Server").build()
        clientCertificate = HeldCertificate.Builder().commonName("Galaxy S24 Ultra").build()

        val handshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .addTrustedCertificate(clientCertificate.certificate)
            .build()

        server = MockWebServer().apply {
            useHttps(handshake.sslSocketFactory(), false)
            // The Filum server requires a client certificate on every resource but /pair (§3.2).
            requireClientAuth()
            start()
        }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun endpoint(): ServerEndpoint =
        ServerEndpoint(server.hostName, server.port, EndpointKind.LAN)

    private fun clientKeyManagers(): Array<KeyManager> = arrayOf(
        FixedKeyManager("telefon", clientCertificate.certificate, clientCertificate.keyPair.private)
    )

    private fun api(
        pin: String = serverPin,
        keyManagers: Array<KeyManager>? = clientKeyManagers(),
    ): FilumApiClient {
        val tls = FilumTls.bundle(pin, keyManagers)
        return FilumApiClient(FilumHttpClients(tls).forKind(EndpointKind.LAN))
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private val pingBody = """
        {"protocol":1,
         "server":{"name":"DESKTOP-PC","version":"1.0.0","instanceId":"abc"},
         "endpoints":["192.168.1.10:8765","100.64.10.20:8765"],
         "storage":{"freeBytes":500,"totalBytes":1000},
         "device":{"label":"Galaxy S24 Ultra","fileCount":7,"bytesStored":123},
         "limits":{"manifestBatch":2000,"smallFileThreshold":262144,
                   "bundleMaxBytes":8388608,"bundleMaxCount":256,"maxPathBytes":4096},
         "time":"2026-08-28T20:00:00Z"}
    """.trimIndent()

    // --- TLS and identity ---

    @Test
    fun `a ping over mTLS returns the server response`() = runTest {
        server.enqueue(json(pingBody))

        val result = api().ping(endpoint())

        val ping = result.expectOk()
        assertEquals(1, ping.protocol)
        assertEquals("DESKTOP-PC", ping.server.name)
        assertEquals(2, ping.endpoints.size)
        assertEquals(2000, ping.limits.manifestBatch)
    }

    @Test
    fun `the client certificate is actually presented`() = runTest {
        // The server has requireClientAuth(), so the request succeeding at all proves
        // FixedKeyManager picked a certificate despite the server naming no authorities.
        server.enqueue(json(pingBody))

        api().ping(endpoint())

        val handshake = server.takeRequest().handshake
        assertNotNull(handshake)
        assertEquals(1, handshake!!.peerCertificates.size)
        assertEquals(
            KeyPin.of(clientCertificate.certificate),
            KeyPin.of(handshake.peerCertificates.first() as java.security.cert.X509Certificate),
        )
    }

    @Test
    fun `a server with a different key is rejected at the TLS level`() = runTest {
        // A substituted server never even gets a chance to answer — that is the point of ADR-005.
        val obcyPin = KeyPin.of(HeldCertificate.Builder().commonName("Obcy").build().certificate)
        server.enqueue(json(pingBody))

        val result = api(pin = obcyPin).ping(endpoint())

        assertInstanceOf(ApiResult.Unreachable::class.java, result)
    }

    @Test
    fun `a missing client certificate ends in a connection error`() = runTest {
        server.enqueue(json(pingBody))

        val result = api(keyManagers = null).ping(endpoint())

        assertInstanceOf(ApiResult.Unreachable::class.java, result)
    }

    // --- manifest ---

    @Test
    fun `the manifest sends a batch and reads the answer`() = runTest {
        server.enqueue(
            json("""{"have":5,"needed":[{"p":"DCIM/a.jpg","offset":1024}],"rejected":[]}""")
        )

        val request = ManifestRequest(
            session = "sesja-1",
            mode = ManifestRequest.MODE_DELTA,
            batch = 0,
            final = true,
            files = listOf(ManifestEntry("DCIM/a.jpg", 2048, 1_756_400_000)),
        )
        val result = api().manifest(endpoint(), request)

        val manifest = result.expectOk()
        assertEquals(5, manifest.have)
        assertEquals(1024, manifest.needed.single().offset)

        val sent = server.takeRequest()
        assertEquals("/api/v1/manifest", sent.path)
        val body = sent.body.readUtf8()
        // The single-letter keys are normative — changing them would break server compatibility.
        assertTrue(body.contains("\"p\":\"DCIM/a.jpg\""), body)
        assertTrue(body.contains("\"s\":2048"), body)
        assertTrue(body.contains("\"m\":1756400000"), body)
    }

    @Test
    fun `rejected paths are read from the manifest response`() = runTest {
        server.enqueue(
            json("""{"have":0,"needed":[],"rejected":[{"p":"../uciekinier.txt","error":"traversal"}]}""")
        )

        val result = api().manifest(
            endpoint(),
            ManifestRequest("s", ManifestRequest.MODE_FULL, 0, true, emptyList()),
        )

        assertEquals("traversal", result.expectOk().rejected.single().error)
    }

    // --- uploading a file ---

    @Test
    fun `put sends the protocol headers and the whole body`(@TempDir dir: Path) = runTest {
        val file = File(dir.toFile(), "foto.jpg").apply { writeText("0123456789ABCDEF") }
        server.enqueue(json("""{"received":16,"offset":16,"complete":true,"versioned":null}"""))

        val result = api().putFile(
            endpoint = endpoint(),
            canonicalPath = "DCIM/Camera/zdjecie-lg.jpg",
            file = file,
            size = 16,
            mtime = 1_756_400_000,
            offset = 0,
            length = 16,
            digest = "a".repeat(64),
        )

        assertInstanceOf(ApiResult.Ok::class.java, result)

        val sent = server.takeRequest()
        assertEquals("PUT", sent.method)
        assertEquals("0123456789ABCDEF", sent.body.readUtf8())
        assertEquals("16", sent.getHeader(FilumHeaders.SIZE))
        assertEquals("1756400000", sent.getHeader(FilumHeaders.MTIME))
        assertEquals("0", sent.getHeader(FilumHeaders.OFFSET))
        assertEquals("a".repeat(64), sent.getHeader(FilumHeaders.DIGEST))
        assertPathHeader(sent, "DCIM/Camera/zdjecie-lg.jpg")
    }

    @Test
    fun `resuming sends only the missing part`(@TempDir dir: Path) = runTest {
        // Without this, resuming is pointless: we would send the file from the start while
        // merely declaring an offset, and the server would splice the bytes out of order.
        val file = File(dir.toFile(), "clip.mp4").apply { writeText("0123456789ABCDEF") }
        server.enqueue(json("""{"received":6,"offset":16,"complete":true,"versioned":null}"""))

        api().putFile(
            endpoint = endpoint(),
            canonicalPath = "Movies/clip.mp4",
            file = file,
            size = 16,
            mtime = 1_756_400_000,
            offset = 10,
            length = 6,
        )

        val sent = server.takeRequest()
        assertEquals("ABCDEF", sent.body.readUtf8())
        assertEquals("10", sent.getHeader(FilumHeaders.OFFSET))
        assertEquals("6", sent.getHeader("Content-Length"))
        assertNull(sent.getHeader(FilumHeaders.DIGEST), "no digest argument means no digest header")
    }

    @Test
    fun `the path travels in the header as base64url without padding`(@TempDir dir: Path) = runTest {
        val file = File(dir.toFile(), "a").apply { writeText("x") }
        server.enqueue(json("""{"received":1,"offset":1,"complete":true}"""))

        api().putFile(endpoint(), "Documents/raport:v2.txt", file, 1, 1, 0, 1)

        val header = server.takeRequest().getHeader(FilumHeaders.PATH)!!
        assertTrue(!header.contains('='), "the padding has to be gone")
        assertTrue(!header.contains('+') && !header.contains('/'), "the URL variant, not plain base64")
        assertEquals(
            "Documents/raport:v2.txt",
            String(Base64.getUrlDecoder().decode(header), Charsets.UTF_8),
        )
    }

    // --- error mapping (§6) ---

    @Test
    fun `an offset conflict carries the expected value`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"offset_mismatch","message":"mismatch","expectedOffset":4}""")
        )

        val file = File.createTempFile("filum", null).apply { writeText("xyz"); deleteOnExit() }
        val result = api().putFile(endpoint(), "a.txt", file, 3, 1, 0, 3)

        val failure = assertInstanceOf(ApiResult.Failure::class.java, result)
        assertEquals(409, failure.status)
        assertEquals("offset_mismatch", failure.code)
        assertEquals(4, failure.expectedOffset)
    }

    @Test
    fun `a rejected path is a permanent error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"traversal","message":"unacceptable"}""")
        )

        val file = File.createTempFile("filum", null).apply { writeText("x"); deleteOnExit() }
        val result = api().putFile(endpoint(), "a.txt", file, 1, 1, 0, 1)

        val failure = assertInstanceOf(ApiResult.Failure::class.java, result)
        assertEquals(FailureKind.PERMANENT, failure.kind)
    }

    @Test
    fun `no space on the server is a transient error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(507).setBody("""{"error":"insufficient_storage"}"""))

        val file = File.createTempFile("filum", null).apply { writeText("x"); deleteOnExit() }
        val result = api().putFile(endpoint(), "a.txt", file, 1, 1, 0, 1)

        assertEquals(FailureKind.TRANSIENT, assertInstanceOf(ApiResult.Failure::class.java, result).kind)
    }

    @Test
    fun `an error with an unreadable body still has a code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>overloaded</html>"))

        val result = api().ping(endpoint())

        val failure = assertInstanceOf(ApiResult.Failure::class.java, result)
        assertEquals("http_503", failure.code)
        assertEquals(FailureKind.TRANSIENT, failure.kind)
    }

    @Test
    fun `a response that is not the Filum protocol is treated as no server`() = runTest {
        // We reached somebody else's service at this address. That is not a protocol error
        // but evidence that the server is not on this route — the race must not pick it.
        server.enqueue(json("<html>witaj na routerze</html>"))

        val result = api().ping(endpoint())

        assertInstanceOf(ApiResult.Unreachable::class.java, result)
    }

    // --- parowanie ---

    @Test
    fun `pairing sends the certificate and the token`() = runTest {
        server.enqueue(json("""{"deviceKey":"PIN-TELEFONU","serverInstanceId":"abc","protocol":1}"""))

        val der = clientCertificate.certificate.encoded
        val result = api().pair(endpoint(), "TOKEN-123", der, "Galaxy S24 Ultra")

        assertEquals("PIN-TELEFONU", result.expectOk().deviceKey)

        val sent = server.takeRequest()
        assertEquals("Bearer TOKEN-123", sent.getHeader("Authorization"))
        val body = sent.body.readUtf8()
        assertTrue(body.contains(Base64.getEncoder().encodeToString(der)))
        assertTrue(body.contains("Galaxy S24 Ultra"))
    }

    private fun assertPathHeader(request: RecordedRequest, expected: String) {
        val header = request.getHeader(FilumHeaders.PATH)!!
        assertEquals(expected, String(Base64.getUrlDecoder().decode(header), Charsets.UTF_8))
    }
}

/**
 * Extracts the value from [ApiResult.Ok] preserving its type. `assertInstanceOf` returns a type
 * with a star projection, so without this helper every field access would land on `Any?`.
 */
private fun <T> ApiResult<T>.expectOk(): T {
    assertInstanceOf(ApiResult.Ok::class.java, this)
    @Suppress("UNCHECKED_CAST")
    return (this as ApiResult.Ok<T>).value
}
