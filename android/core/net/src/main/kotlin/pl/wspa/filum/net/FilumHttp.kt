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
 * When a VPN is running, the application's traffic may enter the tunnel and `192.168.x.x`
 * then stops being reachable (protocol §4.3). Binding the socket to a specific `Network`
 * object from `ConnectivityManager` gets around that. The Android implementation lives in
 * `:core:net`, but the interface lets the clients be tested without it.
 */
fun interface LocalNetworkBinder {
    /** Returns a Wi-Fi-bound socket factory, or `null` when the default route must be used. */
    fun wifiSocketFactory(): SocketFactory?
}

/**
 * HTTP clients for the two ways a route can reach the server.
 *
 * There is more than one client out of necessity rather than convenience: a socket bound to
 * the Wi-Fi network and a socket on the default route behave differently under a VPN, and
 * which of them works depends on the VPN, not on anything the client can read in advance.
 */
class FilumHttpClients(
    private val tls: TlsBundle,
    private val binder: LocalNetworkBinder = LocalNetworkBinder { null },
    private val callTimeoutSeconds: Long = DEFAULT_CALL_TIMEOUT_SECONDS,
    private val connectTimeoutSeconds: Long = DEFAULT_CONNECT_TIMEOUT_SECONDS,
) {

    /** The client on the default route - for overlay routes, pairing and the tests. */
    val defaultRoute: OkHttpClient by lazy { build(socketFactory = null) }

    // Read once per instance, so the routes offered for a race and the clients that serve
    // them agree even if the Wi-Fi network changes in the meantime.
    private val wifiBound: OkHttpClient? by lazy {
        binder.wifiSocketFactory()?.let { build(socketFactory = it) }
    }

    /**
     * The routes to race for the given addresses (§4.3).
     *
     * Every local address is offered twice while a Wi-Fi network is known: bound to it and
     * on the default route. A VPN that captures the local network leaves only the bound
     * socket working; a VPN that forbids bypassing leaves only the default route - and it
     * does not refuse the bound socket, it lets the connection hang. Racing both turns that
     * difference into an ordinary lost probe.
     */
    fun routesFor(candidates: List<ServerEndpoint>): List<ServerEndpoint> {
        if (wifiBound == null) return candidates
        return candidates + candidates.filter { it.kind.isLocal }.map { it.copy(boundToWifi = true) }
    }

    /** The client a route asks for. A bound route falls back to the default one if Wi-Fi is gone. */
    fun forEndpoint(endpoint: ServerEndpoint): OkHttpClient =
        if (endpoint.boundToWifi) wifiBound ?: defaultRoute else defaultRoute

    /** The client used for probing in the race: a short timeout, no retries. */
    fun forProbe(endpoint: ServerEndpoint): OkHttpClient =
        forEndpoint(endpoint).newBuilder()
            .callTimeout(EndpointRacer.DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

    private fun build(socketFactory: SocketFactory?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, tls.trustManager)
            // Safe only together with key pinning - see PinnedHostnameVerifier.
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
