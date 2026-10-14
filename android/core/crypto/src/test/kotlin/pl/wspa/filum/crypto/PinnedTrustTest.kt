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
 * Test vectors: two fixed EC P-256 certificates generated with openssl, together with the
 * fingerprints computed independently using the same tool.
 *
 * The values are hardcoded on purpose. Were the test to generate a certificate on the fly
 * and compute its fingerprint the same way the production code does, it would only confirm
 * that the code agrees with itself - not that it computes what the rest of the world calls
 * odciskiem SubjectPublicKeyInfo.
 */
object TestCertificates {

    /**
     * Odcisk policzony poleceniem:
     * `openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64`
     */
    const val SERVER_PIN = "gjumiJPxlKAE-qwj42AHJcNeusG5O8ZuCpojUP1CAgA"
    const val FOREIGN_PIN = "-fQN2xzXFPl_DhDbybmKei3p2Jg_sAAsm57ZHxXMItw"

    private const val SERVER_DER =
        "MIIBKzCB0QIUfld9Kn6E067N2ugdEjekOASV+k8wCgYIKoZIzj0EAwIwFzEVMBMGA1UEAwwMRmlsdW0gc2Vyd2VyMCAX" +
            "DTI2MDgyODIyMDQzNloYDzIwNzYwODE1MjIwNDM2WjAXMRUwEwYDVQQDDAxGaWx1bSBzZXJ3ZXIwWTATBgcqhkjOPQIB" +
            "BggqhkjOPQMBBwNCAASwCl2oQUdMj1upQ150J9U/xvFUrVibWPjX+1wAwnhnT/Rxv3lV1doF5pFRVxrzYHJYuSIYb2nK" +
            "sd5zUaKvZkF2MAoGCCqGSM49BAMCA0kAMEYCIQCH6PU+G5V+ScYWo3nxtr8HvqzngDFe554S2sUGE7ElxQIhANO62i+X" +
            "dX87xwo+Vw1d9SptSwUMuvoodd6a+RcywTD2"

    private const val FOREIGN_DER =
        "MIIBJjCBzQIUZ8U/+uoSiGSWAJSGO899UfFYXFUwCgYIKoZIzj0EAwIwFTETMBEGA1UEAwwKRmlsdW0gb2JjeTAgFw0y" +
            "NjA4MjgyMjA0MzdaGA8yMDc2MDgxNTIyMDQzN1owFTETMBEGA1UEAwwKRmlsdW0gb2JjeTBZMBMGByqGSM49AgEGCCqG" +
            "SM49AwEHA0IABB7XTO7NMTD3ivuSIrzxUqcBcnoRzCgo26RlYvgicxP3iXXAnyz0r5p3p+Px/j8Ma1NKz/DnL4V0G7vy" +
            "ySfqlbYwCgYIKoZIzj0EAwIDSAAwRQIhALO94W9OpinEytqVrmJ2VS8Dnw0FwMuHqM3ssbTe+bibAiBoD0gVyuoTjFSL" +
            "YSLPBSWuJU8t2HcvbsHJdVKbH4Mmrw=="

    val server: X509Certificate get() = parse(SERVER_DER)
    val foreign: X509Certificate get() = parse(FOREIGN_DER)

    private fun parse(base64: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64)))
                as X509Certificate
}

class KeyPinTest {

    @Test
    fun `the fingerprint matches the value computed by openssl`() {
        assertEquals(TestCertificates.SERVER_PIN, KeyPin.of(TestCertificates.server))
        assertEquals(TestCertificates.FOREIGN_PIN, KeyPin.of(TestCertificates.foreign))
    }

    @Test
    fun `the fingerprint is 43 characters and base64url`() {
        val pin = KeyPin.of(TestCertificates.server)

        assertEquals(KeyPin.LENGTH, pin.length)
        assertFalse(pin.contains('='))
        assertFalse(pin.contains('+'))
        assertFalse(pin.contains('/'))
    }

    @Test
    fun `different keys give different fingerprints`() {
        assertTrue(KeyPin.of(TestCertificates.server) != KeyPin.of(TestCertificates.foreign))
    }

    @Test
    fun `the constant-time comparison behaves like ordinary equality`() {
        val pin = TestCertificates.SERVER_PIN

        assertTrue(KeyPin.matches(pin, pin))
        assertFalse(KeyPin.matches(pin, TestCertificates.FOREIGN_PIN))
        assertFalse(KeyPin.matches(pin, pin.dropLast(1)))
        assertFalse(KeyPin.matches(pin, pin.dropLast(1) + "X"))
    }

    @Test
    fun `the fingerprint format is validated`() {
        assertTrue(KeyPin.isWellFormed(TestCertificates.SERVER_PIN))
        assertTrue(KeyPin.isWellFormed(TestCertificates.FOREIGN_PIN), "a fingerprint may start with '-'")
        assertFalse(KeyPin.isWellFormed("za krotki"))
        assertFalse(KeyPin.isWellFormed("!".repeat(KeyPin.LENGTH)))
    }
}

class PinnedTrustManagerTest {

    @Test
    fun `a server with the remembered key is accepted`() {
        val manager = PinnedTrustManager(TestCertificates.SERVER_PIN)

        manager.checkServerTrusted(arrayOf(TestCertificates.server), "ECDHE_ECDSA")
    }

    @Test
    fun `a server with a different key is rejected`() {
        // This is what ADR-005 actually says: having some certificate is not enough, you
        // need this particular server's private key.
        val manager = PinnedTrustManager(TestCertificates.SERVER_PIN)

        val error = assertThrows(PinMismatchException::class.java) {
            manager.checkServerTrusted(arrayOf(TestCertificates.foreign), "ECDHE_ECDSA")
        }
        assertTrue(error.message!!.contains(TestCertificates.SERVER_PIN))
    }

    @Test
    fun `the first certificate in the chain is the decisive one`() {
        // An attacker could add a trusted certificate further down the chain, hoping the
        // verification would accept any of them.
        val manager = PinnedTrustManager(TestCertificates.SERVER_PIN)

        assertThrows(PinMismatchException::class.java) {
            manager.checkServerTrusted(
                arrayOf(TestCertificates.foreign, TestCertificates.server), "ECDHE_ECDSA",
            )
        }
    }

    @Test
    fun `an empty chain is rejected`() {
        val manager = PinnedTrustManager(TestCertificates.SERVER_PIN)

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(emptyArray(), "ECDHE_ECDSA")
        }
        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(null, "ECDHE_ECDSA")
        }
    }

    @Test
    fun `we accept no certificate authority`() {
        assertEquals(0, PinnedTrustManager(TestCertificates.SERVER_PIN).acceptedIssuers.size)
    }

    @Test
    fun `verifying clients is a usage error`() {
        val manager = PinnedTrustManager(TestCertificates.SERVER_PIN)

        assertThrows(CertificateException::class.java) {
            manager.checkClientTrusted(arrayOf(TestCertificates.server), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `a malformed fingerprint is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { PinnedTrustManager("bzdura") }
    }
}
