package pl.wspa.filum.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/** A server found on the local network. */
data class DiscoveredServer(
    val host: String,
    val port: Int,
    /** The key fingerprint from the TXT record; it rejects someone else's instance without connecting. */
    val pin: String?,
    val name: String?,
) {
    fun toEndpoint(): ServerEndpoint = ServerEndpoint(host, port, EndpointKind.MDNS)
}

/**
 * Discovering the server on the local network over mDNS (protocol §4.1).
 *
 * The route set refreshes itself from the ping response (§4.4), but it has one gap: it
 * works only when **at least one** stored route works. A phone that was away from home
 * during the week the router changed, and has no overlay network, has nothing to bootstrap
 * from. That is exactly the situation mDNS closes.
 */
class MdnsDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsd: NsdManager? = appContext.getSystemService(NsdManager::class.java)
    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)

    /**
     * Looks for servers for a given time and returns everything it found.
     *
     * We wait out the full window rather than stopping at the first hit: several instances may
     * be on the network and the one we want has the right key, not necessarily the
     * fastest answer.
     */
    suspend fun discover(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): List<DiscoveredServer> {
        val manager = nsd ?: return emptyList()

        // Without a multicast lock many devices receive no mDNS answers at all -
        // Wi-Fi filters out broadcast frames to save power.
        val lock = acquireMulticastLock()
        val found = ConcurrentHashMap<String, DiscoveredServer>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) = resolve(manager, service, found)
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String?) = Unit
            override fun onDiscoveryStopped(type: String?) = Unit

            override fun onStartDiscoveryFailed(type: String?, error: Int) {
                Log.w(TAG, "Could not start mDNS discovery: error $error")
            }

            override fun onStopDiscoveryFailed(type: String?, error: Int) = Unit
        }

        return try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(timeoutMillis)
            found.values.toList()
        } catch (e: Exception) {
            // Discovery is a convenience, not a precondition. A firewall, a network with no
            // multicast support, or a refusal from the system must not abort the cycle.
            Log.w(TAG, "mDNS discovery failed", e)
            emptyList()
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { lock?.release() }
        }
    }

    /** Finds the server with a specific key fingerprint; other instances are ignored. */
    suspend fun findByPin(pin: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): List<ServerEndpoint> =
        discover(timeoutMillis)
            .filter { it.pin == null || it.pin == pin }
            .map { it.toEndpoint() }

    private fun resolve(
        manager: NsdManager,
        service: NsdServiceInfo,
        found: ConcurrentHashMap<String, DiscoveredServer>,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Since Android 14 resolveService is deprecated and can refuse when several
            // resolutions run in parallel. The new API calls back on every change, so it
            // has to be unregistered after the first result.
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    record(info, found)
                    runCatching { manager.unregisterServiceInfoCallback(this) }
                }

                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit

                override fun onServiceInfoCallbackRegistrationFailed(error: Int) {
                    Log.w(TAG, "Could not read the service details: error $error")
                }
            }
            runCatching { manager.registerServiceInfoCallback(service, Runnable::run, callback) }
        } else {
            @Suppress("DEPRECATION")
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) = record(info, found)

                override fun onResolveFailed(info: NsdServiceInfo?, error: Int) {
                    Log.w(TAG, "Could not read the service details: error $error")
                }
            })
        }
    }

    private fun record(info: NsdServiceInfo, found: ConcurrentHashMap<String, DiscoveredServer>) {
        val host = addressOf(info) ?: return
        if (info.port !in 1..65_535) return

        val server = DiscoveredServer(
            host = host,
            port = info.port,
            pin = attribute(info, "k"),
            name = attribute(info, "n"),
        )

        found["$host:${info.port}"] = server
        Log.d(TAG, "Found a server: $server")
    }

    /**
     * We take IPv4 addresses only. The server listens on both families anyway, and a
     * link-local IPv6 address needs an interface identifier that we would lose on the way
     * through the route set.
     */
    private fun addressOf(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return info.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
        }

        @Suppress("DEPRECATION")
        return (info.host as? Inet4Address)?.hostAddress
    }

    private fun attribute(info: NsdServiceInfo, key: String): String? =
        info.attributes[key]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    companion object {
        private const val TAG = "FilumMdns"
        private const val MULTICAST_LOCK_TAG = "filum:mdns"

        /** The service type from protocol §4.1. NsdManager expects it with a trailing dot. */
        const val SERVICE_TYPE = "_filum._tcp."

        /**
         * The discovery window. Longer than the route race timeout, because mDNS needs several
         * messages exchanged - but we only reach for it once the stored routes
         * have failed, so that cost never falls on an ordinary cycle.
         */
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}
