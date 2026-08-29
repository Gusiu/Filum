package pl.wspa.filum.crypto

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Menedżer klucza, który zawsze przedstawia ten jeden certyfikat.
 *
 * Świadomie **nie** używamy [javax.net.ssl.KeyManagerFactory]: standardowa implementacja
 * wybiera certyfikat na podstawie listy urzędów certyfikacji, których serwer oczekuje
 * w komunikacie CertificateRequest. Serwer Filum żadnej listy nie wysyła, bo nie ma
 * urzędu — uwierzytelnia po odcisku klucza (ADR-005). Domyślny menedżer nie znalazłby
 * więc dopasowania i uścisk dłoni poszedłby **bez certyfikatu klienta**, co serwer
 * odrzuciłby kodem 401 przy każdym żądaniu.
 *
 * Jest to zarazem jedyny sensowny wybór: telefon ma dokładnie jedną tożsamość, więc
 * nie ma czego wybierać.
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

    // Telefon nigdy nie występuje w roli serwera TLS.
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?, issuers: Array<out Principal>?, socket: Socket?,
    ): String? = null
}
