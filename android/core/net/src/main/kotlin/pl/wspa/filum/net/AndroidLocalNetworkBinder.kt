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
 * Przypięcie gniazd do sieci Wi-Fi z pominięciem tunelu VPN (§4.3 protokołu).
 *
 * Gdy na telefonie działa VPN, system domyślnie wpycha cały ruch aplikacji do tunelu.
 * Adres nakładkowy wtedy zadziała, ale `192.168.1.10` już nie — pakiet poleci do tunelu
 * zamiast do sieci lokalnej. Rozwiązaniem jest poproszenie o konkretny obiekt
 * [Network] i użycie jego fabryki gniazd.
 *
 * Zastrzeżenie do sprawdzenia na urządzeniu docelowym: jeżeli VPN działa w trybie
 * blokującym ruch poza tunelem, przypięcie i tak zawiedzie. Wtedy trasa lokalna po
 * prostu przepada w wyścigu, a wygrywa nakładkowa — użytkownik nie widzi błędu,
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
            Log.d(TAG, "Sieć Wi-Fi dostępna do przypięcia gniazd")
        }

        override fun onLost(network: Network) {
            if (wifi == network) {
                wifi = null
                Log.d(TAG, "Utracono sieć Wi-Fi; trasy lokalne pójdą trasą domyślną")
            }
        }
    }

    init {
        // Żądanie obejmuje wyłącznie TRANSPORT_WIFI, a sieci VPN mają własny transport,
        // więc trafiamy w fizyczne Wi-Fi także wtedy, gdy tunel jest aktywny.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivity?.registerNetworkCallback(request, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Brak uprawnienia ACCESS_NETWORK_STATE; trasy lokalne bez przypięcia", e)
        }
    }

    override fun wifiSocketFactory(): SocketFactory? = wifi?.socketFactory

    override fun close() {
        try {
            connectivity?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Wyrejestrowany już wcześniej — nie ma czego sprzątać.
        }
    }

    private companion object {
        const val TAG = "FilumNetBinder"
    }
}
