package pl.wspa.filum.crypto

import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Zestaw obiektów TLS dla klienta HTTP. Trzymane razem, bo OkHttp wymaga podania
 * fabryki gniazd i menedżera zaufania jednocześnie — rozdzielenie ich prowadzi do
 * konfiguracji, w której przypięcie klucza cicho przestaje działać.
 */
data class TlsBundle(
    val socketFactory: SSLSocketFactory,
    val trustManager: X509TrustManager,
)

object FilumTls {

    /**
     * Buduje TLS przypięty do konkretnego klucza serwera.
     *
     * @param serverPin odcisk klucza serwera z kodu parowania (§3.1)
     * @param identity tożsamość telefonu; `null` wyłącznie na czas parowania, bo
     *   `POST /pair` jest jedynym zasobem dostępnym bez certyfikatu klienta (§3.3)
     */
    fun bundle(serverPin: String, identity: DeviceIdentity?): TlsBundle =
        bundle(serverPin, identity?.keyManagers())

    /**
     * Wariant przyjmujący menedżery klucza wprost. Pozwala przetestować przypięcie
     * i mTLS na zwykłej maszynie wirtualnej, bez Android Keystore i bez emulatora —
     * a to jest ta część, której błąd oznacza wysyłanie kopii pod cudzy adres.
     */
    fun bundle(serverPin: String, keyManagers: Array<KeyManager>?): TlsBundle {
        val trustManager = PinnedTrustManager(serverPin)

        val context = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trustManager), SecureRandom())
        }

        return TlsBundle(context.socketFactory, trustManager)
    }
}
