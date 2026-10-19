package pl.wspa.filum.crypto

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The public key fingerprint: `base64url(SHA-256(SubjectPublicKeyInfo))`, 43 characters
 * (protocol §3.1, ADR-005).
 *
 * Computed over SubjectPublicKeyInfo rather than the whole certificate. That way a pin
 * survives replacing the certificate while keeping the key pair - and a certificate
 * expiring stops being an event that breaks a working installation.
 */
object KeyPin {

    const val LENGTH = 43

    fun of(certificate: X509Certificate): String = ofSpki(certificate.publicKey.encoded)

    /** [spki] is the DER-encoded SubjectPublicKeyInfo - exactly what `PublicKey.getEncoded()` returns. */
    fun ofSpki(spki: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))

    /**
     * A constant-time comparison. The difference is symbolic here, since a key fingerprint
     * is not a secret, but there is no reason to write a secret comparison any other way.
     */
    fun matches(expected: String, actual: String): Boolean {
        if (expected.length != actual.length) return false

        var difference = 0
        for (i in expected.indices) {
            difference = difference or (expected[i].code xor actual[i].code)
        }
        return difference == 0
    }

    fun isWellFormed(pin: String): Boolean =
        pin.length == LENGTH && pin.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
