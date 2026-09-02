package pl.wspa.filum.net

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import pl.wspa.filum.crypto.PinnedHostnameVerifier
import pl.wspa.filum.crypto.TlsBundle
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * The source of socket factories for local routes.
 *
 * When a VPN is running, all of the application's traffic enters the tunnel by default and
 * `192.168.x.x` stops being reachable (protocol §4.3). The fix is to bind the socket; the
 * gniazda do konkretnego obiektu `Network` z `ConnectivityManager`. Implementacja
 * Android implementation lives in `:core:net`, but the interface lets the race be tested without it.
 */
fun interface LocalNetworkBinder {
    /** Returns a Wi-Fi-bound socket factory, or `null` when the default route must be used. */
    fun wifiSocketFactory(): SocketFactory?
}

/**
 * HTTP clients for the individual route kinds.
 *
 * There is more than one client out of necessity rather than convenience: a local route has
 * to go over a bound socket and an overlay route over the tunnel. One client cannot do both
 * at once.
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
     * The client for a given route. For local routes we try a bound socket, and when the VPN
     * blocks traffic outside the tunnel and the binding fails, we quietly fall back to the
     * default route — there is nothing to inform the user about, since the overlay route is
     * about to win anyway (§4.3).
     */
    fun forKind(kind: EndpointKind): OkHttpClient =
        if (kind.isLocal) localBound ?: base else base

    /** The client used for probing in the race: a short timeout, no retries. */
    fun forProbe(kind: EndpointKind): OkHttpClient =
        forKind(kind).newBuilder()
            .callTimeout(EndpointRacer.DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private fun build(socketFactory: SocketFactory?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, tls.trustManager)
            // Safe only together with key pinning — see PinnedHostnameVerifier.
            .hostnameVerifier(PinnedHostnameVerifier)
            // The server is self-signed and modern; there is no reason to allow
            // negotiations from the days of supporting ancient middleboxes.
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            // Transferring a multi-gigabyte file can be slow; we do not want to drop
            // the connection merely because a single write is taking a while.
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (socketFactory != null) builder.socketFactory(socketFactory)

        return builder.build()
    }

    companion object {
        /** The whole transfer of one chunk; 0 would mean no limit, which hides hangs. */
        const val DEFAULT_CALL_TIMEOUT_SECONDS = 15L * 60
        const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10L
        const val WRITE_TIMEOUT_SECONDS = 120L
        const val READ_TIMEOUT_SECONDS = 60L
    }
}
