package pl.wspa.filum.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Wektory testowe: dwa stałe certyfikaty EC P-256 wygenerowane openssl-em, wraz
 * z odciskami policzonymi niezależnie tym samym narzędziem.
 *
 * Wartości są wpisane na sztywno celowo. Gdyby test generował certyfikat w locie
 * i liczył odcisk tą samą metodą co kod produkcyjny, potwierdzałby jedynie, że kod
 * jest zgodny sam ze sobą — a nie że liczy to, co reszta świata nazywa
 * odciskiem SubjectPublicKeyInfo.
 */
object TestCertificates {

    /**
     * Odcisk policzony poleceniem:
     * `openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64`
     */
    const val SERWER_PIN = "gjumiJPxlKAE-qwj42AHJcNeusG5O8ZuCpojUP1CAgA"
    const val OBCY_PIN = "-fQN2xzXFPl_DhDbybmKei3p2Jg_sAAsm57ZHxXMItw"

    private const val SERWER_DER =
        "MIIBKzCB0QIUfld9Kn6E067N2ugdEjekOASV+k8wCgYIKoZIzj0EAwIwFzEVMBMGA1UEAwwMRmlsdW0gc2Vyd2VyMCAX" +
            "DTI2MDgyODIyMDQzNloYDzIwNzYwODE1MjIwNDM2WjAXMRUwEwYDVQQDDAxGaWx1bSBzZXJ3ZXIwWTATBgcqhkjOPQIB" +
            "BggqhkjOPQMBBwNCAASwCl2oQUdMj1upQ150J9U/xvFUrVibWPjX+1wAwnhnT/Rxv3lV1doF5pFRVxrzYHJYuSIYb2nK" +
            "sd5zUaKvZkF2MAoGCCqGSM49BAMCA0kAMEYCIQCH6PU+G5V+ScYWo3nxtr8HvqzngDFe554S2sUGE7ElxQIhANO62i+X" +
            "dX87xwo+Vw1d9SptSwUMuvoodd6a+RcywTD2"

    private const val OBCY_DER =
        "MIIBJjCBzQIUZ8U/+uoSiGSWAJSGO899UfFYXFUwCgYIKoZIzj0EAwIwFTETMBEGA1UEAwwKRmlsdW0gb2JjeTAgFw0y" +
            "NjA4MjgyMjA0MzdaGA8yMDc2MDgxNTIyMDQzN1owFTETMBEGA1UEAwwKRmlsdW0gb2JjeTBZMBMGByqGSM49AgEGCCqG" +
            "SM49AwEHA0IABB7XTO7NMTD3ivuSIrzxUqcBcnoRzCgo26RlYvgicxP3iXXAnyz0r5p3p+Px/j8Ma1NKz/DnL4V0G7vy" +
            "ySfqlbYwCgYIKoZIzj0EAwIDSAAwRQIhALO94W9OpinEytqVrmJ2VS8Dnw0FwMuHqM3ssbTe+bibAiBoD0gVyuoTjFSL" +
            "YSLPBSWuJU8t2HcvbsHJdVKbH4Mmrw=="

    val serwer: X509Certificate get() = parse(SERWER_DER)
    val obcy: X509Certificate get() = parse(OBCY_DER)

    private fun parse(base64: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64)))
                as X509Certificate
}

class KeyPinTest {

    @Test
    fun `odcisk zgadza sie z wartoscia policzona przez openssl`() {
        assertEquals(TestCertificates.SERWER_PIN, KeyPin.of(TestCertificates.serwer))
        assertEquals(TestCertificates.OBCY_PIN, KeyPin.of(TestCertificates.obcy))
    }

    @Test
    fun `odcisk ma 43 znaki i jest base64url`() {
        val pin = KeyPin.of(TestCertificates.serwer)

        assertEquals(KeyPin.LENGTH, pin.length)
        assertFalse(pin.contains('='))
        assertFalse(pin.contains('+'))
        assertFalse(pin.contains('/'))
    }

    @Test
    fun `rozne klucze daja rozne odciski`() {
        assertTrue(KeyPin.of(TestCertificates.serwer) != KeyPin.of(TestCertificates.obcy))
    }

    @Test
    fun `porownanie odporne na czas dziala jak zwykla rownosc`() {
        val pin = TestCertificates.SERWER_PIN

        assertTrue(KeyPin.matches(pin, pin))
        assertFalse(KeyPin.matches(pin, TestCertificates.OBCY_PIN))
        assertFalse(KeyPin.matches(pin, pin.dropLast(1)))
        assertFalse(KeyPin.matches(pin, pin.dropLast(1) + "X"))
    }

    @Test
    fun `postac odcisku jest sprawdzana`() {
        assertTrue(KeyPin.isWellFormed(TestCertificates.SERWER_PIN))
        assertTrue(KeyPin.isWellFormed(TestCertificates.OBCY_PIN), "odcisk może zaczynać się od '-'")
        assertFalse(KeyPin.isWellFormed("za krotki"))
        assertFalse(KeyPin.isWellFormed("!".repeat(KeyPin.LENGTH)))
    }
}

class PinnedTrustManagerTest {

    @Test
    fun `serwer o zapamietanym kluczu jest akceptowany`() {
        val manager = PinnedTrustManager(TestCertificates.SERWER_PIN)

        manager.checkServerTrusted(arrayOf(TestCertificates.serwer), "ECDHE_ECDSA")
    }

    @Test
    fun `serwer o innym kluczu jest odrzucany`() {
        // To jest właściwa treść ADR-005: nie wystarczy mieć jakikolwiek certyfikat,
        // trzeba mieć klucz prywatny tego konkretnego serwera.
        val manager = PinnedTrustManager(TestCertificates.SERWER_PIN)

        val error = assertThrows(PinMismatchException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.obcy), "ECDHE_ECDSA")
        }
        assertTrue(error.message!!.contains(TestCertificates.SERWER_PIN))
    }

    @Test
    fun `pierwszy certyfikat w lancuchu jest rozstrzygajacy`() {
        // Napastnik mógłby dołożyć zaufany certyfikat dalej w łańcuchu, licząc na to,
        // że weryfikacja przejdzie po którymkolwiek z nich.
        val manager = PinnedTrustManager(TestCertificates.SERWER_PIN)

        assertThrows(PinMismatchException::class.java) {
            manager.checkServerTrusted(
                arrayOf(TestCertificates.obcy, TestCertificates.serwer), "ECDHE_ECDSA",
            )
        }
    }

    @Test
    fun `pusty lancuch jest odrzucany`() {
        val manager = PinnedTrustManager(TestCertificates.SERWER_PIN)

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(null, "ECDHE_ECDSA")
        }
    }

    @Test
    fun `nie akceptujemy zadnego urzedu certyfikacji`() {
        assertEquals(0, PinnedTrustManager(TestCertificates.SERWER_PIN).acceptedIssuers.size)
    }

    @Test
    fun `weryfikacja klientow jest bledem uzycia`() {
        val manager = PinnedTrustManager(TestCertificates.SERWER_PIN)

        assertThrows(CertificateException::class.java) {
            manager.checkClientTrusted(arrayOf(TestCertificates.serwer), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `odcisk o zlej postaci jest odrzucany przy tworzeniu`() {
        assertThrows(IllegalArgumentException::class.java) { PinnedTrustManager("bzdura") }
    }
}
