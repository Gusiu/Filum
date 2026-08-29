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

/** Serwer znaleziony w sieci lokalnej. */
data class DiscoveredServer(
    val host: String,
    val port: Int,
    /** Odcisk klucza z rekordu TXT; pozwala odrzucić cudzą instancję bez łączenia się z nią. */
    val pin: String?,
    val name: String?,
) {
    fun toEndpoint(): ServerEndpoint = ServerEndpoint(host, port, EndpointKind.MDNS)
}

/**
 * Wykrywanie serwera w sieci lokalnej przez mDNS (§4.1 protokołu).
 *
 * Zbiór tras odświeża się sam z odpowiedzi na ping (§4.4), ale ma jedną lukę:
 * działa tylko wtedy, gdy **choć jedna** zapisana trasa zadziała. Telefon, który
 * nie był w domu przez tydzień zmiany routera i nie ma sieci nakładkowej, nie ma
 * się od czego odbić. To jest dokładnie ta sytuacja, którą domyka mDNS.
 */
class MdnsDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsd: NsdManager? = appContext.getSystemService(NsdManager::class.java)
    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)

    /**
     * Szuka serwerów przez zadany czas i zwraca wszystkie znalezione.
     *
     * Czekamy pełne okno zamiast kończyć na pierwszym trafieniu: w sieci może stać
     * kilka instancji, a interesuje nas ta o właściwym kluczu, niekoniecznie
     * najszybsza.
     */
    suspend fun discover(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): List<DiscoveredServer> {
        val manager = nsd ?: return emptyList()

        // Bez blokady multicastu wiele urządzeń w ogóle nie dostaje odpowiedzi mDNS —
        // Wi-Fi odfiltrowuje ramki rozgłoszeniowe, żeby oszczędzać energię.
        val lock = acquireMulticastLock()
        val found = ConcurrentHashMap<String, DiscoveredServer>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) = resolve(manager, service, found)
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String?) = Unit
            override fun onDiscoveryStopped(type: String?) = Unit

            override fun onStartDiscoveryFailed(type: String?, error: Int) {
                Log.w(TAG, "Nie udało się rozpocząć wykrywania mDNS: błąd $error")
            }

            override fun onStopDiscoveryFailed(type: String?, error: Int) = Unit
        }

        return try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(timeoutMillis)
            found.values.toList()
        } catch (e: Exception) {
            // Wykrywanie jest wygodą, nie warunkiem działania. Zapora, brak obsługi
            // multicastu w sieci albo odmowa systemu nie mogą przerwać cyklu.
            Log.w(TAG, "Wykrywanie mDNS zawiodło", e)
            emptyList()
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { lock?.release() }
        }
    }

    /** Znajduje serwer o konkretnym odcisku klucza; ignoruje cudze instancje. */
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
            // Od Androida 14 resolveService jest wycofane i potrafi odmówić, gdy
            // kilka rozwiązań idzie równolegle. Nowe API wywołuje zwrotnie przy
            // każdej zmianie, więc trzeba je wyrejestrować po pierwszym wyniku.
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    record(info, found)
                    runCatching { manager.unregisterServiceInfoCallback(this) }
                }

                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit

                override fun onServiceInfoCallbackRegistrationFailed(error: Int) {
                    Log.w(TAG, "Nie udało się odczytać szczegółów usługi: błąd $error")
                }
            }
            runCatching { manager.registerServiceInfoCallback(service, Runnable::run, callback) }
        } else {
            @Suppress("DEPRECATION")
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) = record(info, found)

                override fun onResolveFailed(info: NsdServiceInfo?, error: Int) {
                    Log.w(TAG, "Nie udało się odczytać szczegółów usługi: błąd $error")
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
        Log.d(TAG, "Znaleziono serwer $server")
    }

    /**
     * Bierzemy wyłącznie adresy IPv4. Serwer i tak nasłuchuje na obu rodzinach,
     * a adres IPv6 o zasięgu łącza wymaga identyfikatora interfejsu, którego
     * po drodze przez zbiór tras byśmy zgubili.
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

        /** Typ usługi z §4.1 protokołu. NsdManager oczekuje go z kropką na końcu. */
        const val SERVICE_TYPE = "_filum._tcp."

        /**
         * Okno wykrywania. Dłuższe niż limit wyścigu tras, bo mDNS wymaga wymiany
         * kilku komunikatów — ale sięgamy po nie dopiero wtedy, gdy zapisane trasy
         * zawiodły, więc ten koszt nie obciąża zwykłego cyklu.
         */
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}
