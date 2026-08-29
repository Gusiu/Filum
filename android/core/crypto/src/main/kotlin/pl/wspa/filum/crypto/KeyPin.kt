package pl.wspa.filum.crypto

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Odcisk klucza publicznego: `base64url(SHA-256(SubjectPublicKeyInfo))`, 43 znaki
 * (§3.1 protokołu, ADR-005).
 *
 * Liczymy z SubjectPublicKeyInfo, a nie z całego certyfikatu. Dzięki temu przypięcie
 * przetrwa wymianę certyfikatu przy zachowaniu tej samej pary kluczy — a wygaśnięcie
 * certyfikatu przestaje być zdarzeniem, które zrywa działającą instalację.
 */
object KeyPin {

    const val LENGTH = 43

    fun of(certificate: X509Certificate): String = ofSpki(certificate.publicKey.encoded)

    /** [spki] to zakodowane DER SubjectPublicKeyInfo — dokładnie to, co zwraca `PublicKey.getEncoded()`. */
    fun ofSpki(spki: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))

    /**
     * Porównanie odporne na czas. Różnica jest tu symboliczna, bo odcisk klucza nie jest
     * sekretem, ale nie ma powodu, żeby pisać porównanie sekretów inaczej niż porządnie.
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
