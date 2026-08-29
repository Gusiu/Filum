package pl.wspa.filum.net

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import pl.wspa.filum.crypto.PinnedHostnameVerifier
import pl.wspa.filum.crypto.TlsBundle
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Źródło fabryk gniazd dla tras lokalnych.
 *
 * Gdy na telefonie działa VPN, cały ruch aplikacji domyślnie wchodzi w tunel i adres
 * `192.168.x.x` przestaje być osiągalny (§4.3 protokołu). Rozwiązaniem jest przypięcie
 * gniazda do konkretnego obiektu `Network` z `ConnectivityManager`. Implementacja
 * androidowa mieszka w `:core:net`, ale interfejs pozwala testować wyścig bez systemu.
 */
fun interface LocalNetworkBinder {
    /** Zwraca fabrykę gniazd związaną z Wi-Fi albo `null`, gdy trzeba użyć trasy domyślnej. */
    fun wifiSocketFactory(): SocketFactory?
}

/**
 * Klienci HTTP dla poszczególnych rodzajów tras.
 *
 * Klientów jest więcej niż jeden nie dla wygody, tylko z konieczności: trasa lokalna
 * musi iść przypiętym gniazdem, a nakładkowa właśnie tunelem. Jeden klient nie może
 * robić obu rzeczy naraz.
 */
class FilumHttpClients(
    private val tls: TlsBundle,
    private val binder: LocalNetworkBinder = LocalNetworkBinder { null },
    private val callTimeoutSeconds: Long = DEFAULT_CALL_TIMEOUT_SECONDS,
    private val connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
) {

    private val base: OkHttpClient by lazy { build(socketFactory = null) }

    private val localBound: OkHttpClient? by lazy {
        binder.wifiSocketFactory()?.let { build(socketFactory = it) }
    }

    /**
     * Klient dla danej trasy. Dla tras lokalnych próbujemy przypiętego gniazda,
     * a gdy VPN działa w trybie blokującym ruch poza tunelem i przypięcie zawiedzie,
     * po cichu wracamy do trasy domyślnej — użytkownik nie ma o czym być informowany,
     * bo i tak zaraz wygra trasa nakładkowa (§4.3).
     */
    fun forKind(kind: EndpointKind): OkHttpClient =
        if (kind.isLocal) localBound ?: base else base

    /** Klient do sondowania w wyścigu: krótki limit czasu, bez ponawiania. */
    fun forProbe(kind: EndpointKind): OkHttpClient =
        forKind(kind).newBuilder()
            .callTimeout(EndpointRacer.DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private fun build(socketFactory: SocketFactory?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, tls.trustManager)
            // Bezpieczne wyłącznie razem z przypięciem klucza — patrz PinnedHostnameVerifier.
            .hostnameVerifier(PinnedHostnameVerifier)
            // Serwer jest samopodpisany i nowoczesny; nie ma powodu dopuszczać
            // uzgodnień z czasów, gdy trzeba było obsługiwać stare pośredniki.
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            // Transfer wielogigabajtowego pliku bywa wolny; nie chcemy zrywać
            // połączenia tylko dlatego, że pojedynczy zapis trwa.
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (socketFactory != null) builder.socketFactory(socketFactory)

        return builder.build()
    }

    companion object {
        /** Cały transfer jednego fragmentu; 0 oznaczałoby brak limitu, co maskuje zawieszenia. */
        const val DEFAULT_CALL_TIMEOUT_SECONDS = 15L * 60
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10L
        const val WRITE_TIMEOUT_SECONDS = 120L
        const val READ_TIMEOUT_SECONDS = 60L
    }
}
