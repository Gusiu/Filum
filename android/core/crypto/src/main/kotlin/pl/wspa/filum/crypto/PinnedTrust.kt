package pl.wspa.filum.crypto

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/** Serwer przedstawił się kluczem, którego nie znamy. */
class PinMismatchException(expected: String, actual: String) : CertificateException(
    "Odcisk klucza serwera nie zgadza się z zapamiętanym przy parowaniu. " +
        "Oczekiwano $expected, otrzymano $actual."
)

/**
 * Zaufanie wyłącznie do jednego, zapamiętanego klucza publicznego (§3.1 protokołu).
 *
 * Nie ma tu żadnego urzędu certyfikacji ani łańcucha zaufania — certyfikat serwera jest
 * samopodpisany z definicji i służy tylko za pojemnik na klucz. Jedynym kryterium jest
 * zgodność odcisku z tym, który przyszedł w kodzie QR.
 *
 * Model jest mocniejszy niż zwykły TLS z publicznym urzędem: nie wystarczy przejąć
 * kontrolę nad dowolnym zaufanym CA, trzeba mieć konkretny klucz prywatny tego serwera.
 */
class PinnedTrustManager(private val expectedPin: String) : X509TrustManager {

    init {
        require(KeyPin.isWellFormed(expectedPin)) { "Odcisk klucza ma niepoprawną postać: $expectedPin" }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("Serwer nie przedstawił żadnego certyfikatu.")

        val actual = KeyPin.of(leaf)
        if (!KeyPin.matches(expectedPin, actual)) throw PinMismatchException(expectedPin, actual)
    }

    /**
     * Klient nigdy nie weryfikuje cudzych certyfikatów klienta — to rola serwera.
     * Wywołanie tej metody oznaczałoby użycie obiektu w kontekście, do którego nie należy.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("PinnedTrustManager nie służy do weryfikacji klientów.")
    }

    /** Pusta lista jest poprawna: nie akceptujemy żadnego urzędu, tylko konkretny klucz. */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Weryfikator nazwy hosta, który przepuszcza każdą nazwę.
 *
 * **Jest to bezpieczne wyłącznie w połączeniu z [PinnedTrustManager]** i wyłącznie dlatego,
 * że przypięcie klucza całkowicie zastępuje weryfikację nazwy. Serwer jest osiągalny pod
 * wieloma adresami naraz (LAN, sieć nakładkowa, mDNS — §4 protokołu), a wszystkie
 * wskazują na ten sam klucz; wymaganie pól SAN dla każdego z nich byłoby niewykonalne,
 * bo adresy zmieniają się w czasie.
 *
 * Usunięcie [PinnedTrustManager] przy pozostawieniu tego weryfikatora otwiera połączenie
 * na dowolny serwer. Te dwie klasy wolno używać tylko razem.
 */
object PinnedHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean = true
}
