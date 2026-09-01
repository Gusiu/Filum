package pl.wspa.filum.crypto

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/** The server presented a key we do not know. */
class PinMismatchException(expected: String, actual: String) : CertificateException(
    "The server key fingerprint does not match the one remembered at pairing time. " +
        "Oczekiwano $expected, otrzymano $actual."
)

/**
 * Trust in exactly one remembered public key (protocol §3.1).
 *
 * There is no certificate authority here and no trust chain — the server certificate is
 * self-signed by definition and serves purely as a container for the key. The only
 * criterion is that the fingerprint matches the one that arrived in the QR code.
 *
 * The model is stronger than ordinary TLS with a public authority: seizing control of any
 * trusted CA is not enough, you need this particular server's private key.
 */
class PinnedTrustManager(private val expectedPin: String) : X509TrustManager {

    init {
        require(KeyPin.isWellFormed(expectedPin)) { "The key fingerprint is malformed: $expectedPin" }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("The server presented no certificate at all.")

        val actual = KeyPin.of(leaf)
        if (!KeyPin.matches(expectedPin, actual)) throw PinMismatchException(expectedPin, actual)
    }

    /**
     * The client never verifies anyone else's client certificates — that is the server's
     * role. Calling this method would mean using the object where it does not belong.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("PinnedTrustManager is not for verifying clients.")
    }

    /** An empty list is correct: we accept no authority, only one specific key. */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * A hostname verifier that lets every name through.
 *
 * **This is safe only together with [PinnedTrustManager]**, and only because key pinning
 * fully replaces name verification. The server is reachable at several addresses at once
 * (LAN, overlay network, mDNS — protocol §4), all of them pointing at the same key;
 * requiring SAN entries for each would be unworkable, because the addresses change over
 * time.
 *
 * Removing [PinnedTrustManager] while leaving this verifier in place opens the connection
 * to any server at all. These two classes may only be used together.
 */
object PinnedHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean = true
}
