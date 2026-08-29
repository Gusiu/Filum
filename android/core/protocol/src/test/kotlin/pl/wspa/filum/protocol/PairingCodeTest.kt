package pl.wspa.filum.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Odczyt kodu parowania (§3.3 protokołu).
 *
 * Kod QR jest jedynym momentem, w którym telefon dowiaduje się, komu ufać. Przyjęcie
 * uszkodzonego albo podmienionego ładunku oznaczałoby wysyłanie kopii zapasowej pod
 * cudzy adres, więc walidacja jest tu celowo restrykcyjna.
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
    fun `poprawny kod jest odczytany w calosci`() {
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
    fun `biale znaki wokol kodu sa tolerowane`() {
        // Użytkownik wkleja ciąg ze schowka; nowa linia na końcu to norma, nie błąd.
        val result = PairingCode.parse("  ${payload()}\n")

        assertInstanceOf(PairingCode.Result.Ok::class.java, result)
    }

    @Test
    fun `nieznane pola nie psuja odczytu`() {
        // Serwer w nowszej wersji może dołożyć pole; stary klient ma to przeżyć,
        // bo inaczej aktualizacja serwera zerwałaby parowanie na wszystkich telefonach.
        val withExtra = code(
            """{"n":"DESKTOP-PC","k":"$poprawnyPin","t":"T","p":8765,"a":["10.0.0.1:8765"],"s":"x","nowe":42}"""
        )

        assertInstanceOf(PairingCode.Result.Ok::class.java, PairingCode.parse(withExtra))
    }

    @Test
    fun `brak prefiksu jest odrzucany`() {
        assertTrue(assertInvalid("https://example.com").contains("filum1:"))
    }

    @Test
    fun `uszkodzone kodowanie jest odrzucane`() {
        assertTrue(assertInvalid(PairingCode.PREFIX + "!!!nie-base64!!!").contains("kodowanie"))
    }

    @Test
    fun `tresc niebedaca ladunkiem parowania jest odrzucana`() {
        assertTrue(assertInvalid(code("""{"cos":"innego"}""")).contains("strukturę"))
    }

    @Test
    fun `odcisk klucza o zlej dlugosci jest odrzucany`() {
        // 43 znaki to base64url z SHA-256. Krótszy odcisk oznacza albo uszkodzony kod,
        // albo próbę podstawienia słabszego identyfikatora.
        assertTrue(assertInvalid(payload(key = "za-krotki")).contains("Odcisk"))
    }

    @Test
    fun `pusty token jest odrzucany`() {
        assertTrue(assertInvalid(payload(token = "")).contains("token"))
    }

    @Test
    fun `port poza zakresem jest odrzucany`() {
        assertTrue(assertInvalid(payload(port = 0)).contains("Port"))
        assertTrue(assertInvalid(payload(port = 70_000)).contains("Port"))
    }

    @Test
    fun `kod bez adresow jest odrzucany`() {
        assertTrue(assertInvalid(payload(addresses = "[]")).contains("adresu"))
    }
}

/** Klasyfikacja błędów HTTP (§6 protokołu). */
class HttpStatusTest {

    @Test
    fun `bledy przejsciowe nadaja sie do ponowienia`() {
        for (code in listOf(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE, 500, 502)) {
            assertEquals(FailureKind.TRANSIENT, HttpStatus.classify(code), "kod $code")
        }
    }

    @Test
    fun `bledy trwale nie wracaja do kolejki`() {
        // Plik odrzucony przez 422 wracałby w nieskończoność, gdyby nie to rozróżnienie.
        for (code in listOf(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE, HttpStatus.FORBIDDEN)) {
            assertEquals(FailureKind.PERMANENT, HttpStatus.classify(code), "kod $code")
        }
    }

    @Test
    fun `brak miejsca na serwerze jest przejsciowy`() {
        // Użytkownik może zwolnić miejsce; to nie jest wada pliku.
        assertEquals(FailureKind.TRANSIENT, HttpStatus.classify(HttpStatus.INSUFFICIENT_STORAGE))
    }
}
