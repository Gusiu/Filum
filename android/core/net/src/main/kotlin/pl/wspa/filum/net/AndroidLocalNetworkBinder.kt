package pl.wspa.filum.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.io.Closeable
import javax.net.SocketFactory

/**
 * Binding sockets to the Wi-Fi network, bypassing the VPN tunnel (protocol §4.3).
 *
 * When a VPN is running, the system pushes all of an application's traffic into the tunnel
 * by default. An overlay address then works, but `192.168.1.10` does not — the packet goes
 * to the tunnel instead of the local network. The fix is to ask for a specific [Network]
 * object and use its socket factory.
 *
 * A caveat to verify on the target device: if the VPN blocks traffic outside the tunnel,
 * the binding will fail anyway. The local route then simply loses the race and the overlay
 * one wins — the user sees no error,
 * tylko wolniejszy transfer w domu.
 *
 * Wymaga uprawnienia `ACCESS_NETWORK_STATE`.
 */
class AndroidLocalNetworkBinder(context: Context) : LocalNetworkBinder, Closeable {

    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var wifi: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifi = network
            Log.d(TAG, "A Wi-Fi network is available for socket binding")
        }

        override fun onLost(network: Network) {
            if (wifi == network) {
                wifi = null
                Log.d(TAG, "Lost the Wi-Fi network; local routes will use the default route")
            }
        }
    }

    init {
        // The request covers TRANSPORT_WIFI only, and VPN networks have their own transport,
        // so we reach the physical Wi-Fi even while the tunnel is up.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivity?.registerNetworkCallback(request, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "No ACCESS_NETWORK_STATE permission; local routes will go unbound", e)
        }
    }

    override fun wifiSocketFactory(): SocketFactory? = wifi?.socketFactory

    override fun close() {
        try {
            connectivity?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Already unregistered — there is nothing to clean up.
        }
    }

    private companion object {
        const val TAG = "FilumNetBinder"
    }
}
