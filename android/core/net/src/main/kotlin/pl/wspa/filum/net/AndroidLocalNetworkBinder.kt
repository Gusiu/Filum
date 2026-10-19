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
 * When a VPN routes everything into its tunnel, an overlay address works but `192.168.1.10`
 * does not - the packet goes to the tunnel instead of the local network. Asking for a
 * specific [Network] object and using its socket factory gets around that.
 *
 * It does not always help. A VPN that forbids bypassing (`bypassable=false`, measured with
 * NordVPN on a Galaxy S24 Ultra) does not refuse a bound socket: the connection simply hangs.
 * The same VPN may leave the local network out of the tunnel, so the default route works.
 * That is why [FilumHttpClients.routesFor] races every local address both ways.
 *
 * Requires the `ACCESS_NETWORK_STATE` permission.
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
        // A VPN running over Wi-Fi reports TRANSPORT_WIFI next to TRANSPORT_VPN, so the
        // transport alone does not tell the tunnel from the physical network. NOT_VPN is
        // part of a new request by default; it is stated here because binding to the
        // tunnel would defeat the whole purpose.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
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
            // Already unregistered - there is nothing to clean up.
        }
    }

    private companion object {
        const val TAG = "FilumNetBinder"
    }
}
