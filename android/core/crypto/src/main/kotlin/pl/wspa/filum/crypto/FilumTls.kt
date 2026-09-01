package pl.wspa.filum.crypto

import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * The TLS objects for the HTTP client. Kept together because OkHttp requires the socket
 * factory and the trust manager to be supplied at the same time — separating them leads to
 * a configuration in which key pinning silently stops working.
 */
data class TlsBundle(
    val socketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager,
)

object FilumTls {

    /**
     * Builds TLS pinned to one specific server key.
     *
     * @param serverPin the server key fingerprint from the pairing code (§3.1)
     * @param identity the phone's identity; `null` only during pairing, because `POST /pair`
     *   is the one resource reachable without a client certificate (§3.3)
     */
    fun bundle(serverPin: String, identity: DeviceIdentity?): TlsBundle =
        bundle(serverPin, identity?.keyManagers())

    /**
     * A variant taking key managers directly. It allows pinning and mTLS to be tested on an
     * ordinary JVM, with no Android Keystore and no emulator — and this is the part where a
     * mistake means uploading the backup to somebody else's address.
     */
    fun bundle(serverPin: String, keyManagers: Array<KeyManager>?): TlsBundle {
        val trustManager = PinnedTrustManager(serverPin)

        val context = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trustManager), SecureRandom())
        }

        return TlsBundle(context.socketFactory, trustManager)
    }
}
