package pl.wspa.filum.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Parsing the pairing code (protocol §3.3).
 *
 * The QR code is the one moment at which the phone learns whom to trust. Accepting a
 * corrupted or substituted payload would mean uploading the backup to somebody else's
 * address, so the validation here is deliberately strict.
 */
class PairingCodeTest {

    private val poprawnyPin = "A".repeat(43)

    private fun code(json: String): String =
        PairingCode.PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(Charsets.UTF_8))

    private fun payload(
        key: String = poprawnyPin,
        token: String = "TOKEN-PAROWANIA",
        port: Int = 8765,
        addresses: String = """["192.168.1.10:8765","100.64.10.20:8765"]""",
    ) = code(
        """{"n":"DESKTOP-PC","k":"$key","t":"$token","p":$port,"a":$addresses,"s":"filum-abcd1234"}"""
    )

    private fun assertInvalid(raw: String): String {
        val result = PairingCode.parse(raw)
        return assertInstanceOf(PairingCode.Result.Invalid::class.java, result).reason
    }

    @Test
    fun `a valid code is parsed in full`() {
        val result = PairingCode.parse(payload())

        val ok = assertInstanceOf(PairingCode.Result.Ok::class.java, result)
        assertEquals("DESKTOP-PC", ok.payload.name)
        assertEquals(poprawnyPin, ok.payload.key)
        assertEquals("TOKEN-PAROWANIA", ok.payload.token)
        assertEquals(8765, ok.payload.port)
        assertEquals(2, ok.payload.addresses.size)
        assertEquals("filum-abcd1234", ok.payload.service)
    }

    @Test
    fun `whitespace around the code is tolerated`() {
        // The user pastes the string from the clipboard; a trailing newline is normal, not an error.
        val result = PairingCode.parse("  ${payload()}\n")

        assertInstanceOf(PairingCode.Result.Ok::class.java, result)
    }

    @Test
    fun `unknown fields do not break parsing`() {
        // A newer server may add a field; an older client has to survive that, or updating
        // the server would break pairing on every phone at once.
        val withExtra = code(
            """{"n":"DESKTOP-PC","k":"$poprawnyPin","t":"T","p":8765,"a":["10.0.0.1:8765"],"s":"x","nowe":42}"""
        )

        assertInstanceOf(PairingCode.Result.Ok::class.java, PairingCode.parse(withExtra))
    }

    @Test
    fun `a missing prefix is rejected`() {
        assertTrue(assertInvalid("https://example.com").contains("filum1:"))
    }

    @Test
    fun `broken encoding is rejected`() {
        assertTrue(assertInvalid(PairingCode.PREFIX + "!!!not-base64!!!").contains("encoding"))
    }

    @Test
    fun `content that is not a pairing payload is rejected`() {
        assertTrue(assertInvalid(code("""{"something":"else"}""")).contains("structure"))
    }

    @Test
    fun `a key fingerprint of the wrong length is rejected`() {
        // 43 characters is base64url of SHA-256. A shorter fingerprint means either a
        // damaged code or an attempt to substitute a weaker identifier.
        assertTrue(assertInvalid(payload(key = "too-short")).contains("fingerprint"))
    }

    @Test
    fun `an empty token is rejected`() {
        assertTrue(assertInvalid(payload(token = "")).contains("token"))
    }

    @Test
    fun `a port out of range is rejected`() {
        assertTrue(assertInvalid(payload(port = 0)).contains("Port"))
        assertTrue(assertInvalid(payload(port = 70_000)).contains("Port"))
    }

    @Test
    fun `a code with no addresses is rejected`() {
        assertTrue(assertInvalid(payload(addresses = "[]")).contains("address"))
    }
}

/** Classification of HTTP errors (protocol §6). */
class HttpStatusTest {

    @Test
    fun `transient errors are worth retrying`() {
        for (code in listOf(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE, 500, 502)) {
            assertEquals(FailureKind.TRANSIENT, HttpStatus.classify(code), "kod $code")
        }
    }

    @Test
    fun `permanent errors do not return to the queue`() {
        // A file rejected with 422 would return forever were it not for this distinction.
        for (code in listOf(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE, HttpStatus.FORBIDDEN)) {
            assertEquals(FailureKind.PERMANENT, HttpStatus.classify(code), "kod $code")
        }
    }

    @Test
    fun `no space on the server is transient`() {
        // The user can free up space; this is not a defect in the file.
        assertEquals(FailureKind.TRANSIENT, HttpStatus.classify(HttpStatus.INSUFFICIENT_STORAGE))
    }
}
