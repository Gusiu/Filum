package pl.wspa.filum.crypto

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * A key manager that always presents this one certificate.
 *
 * We deliberately do **not** use [javax.net.ssl.KeyManagerFactory]: the standard
 * implementation picks a certificate from the list of authorities the server names in its
 * CertificateRequest. The Filum server sends no such list, because it has no authority -
 * it authenticates by key fingerprint (ADR-005). The default manager would therefore find
 * no match and the handshake would proceed **without a client certificate**, which the
 * server would refuse with 401 on every request.
 *
 * It is also the only sensible choice: the phone has exactly one identity, so there is
 * nothing to choose between.
 */
class FixedKeyManager(
    private val alias: String,
    certificate: X509Certificate,
    private val privateKey: PrivateKey,
) : X509ExtendedKeyManager() {

    private val chain = arrayOf(certificate)

    override fun chooseClientAlias(
        keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?,
    ): String = alias

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?,
    ): String = alias

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        arrayOf(alias)

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

    override fun getPrivateKey(alias: String?): PrivateKey = privateKey

    // The phone never acts as a TLS server.
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?, issuers: Array<out Principal>?, socket: Socket?,
    ): String? = null
}
