package pl.wspa.filum.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import pl.wspa.filum.crypto.FilumTls
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Which routes are raced and which client serves each of them (protocol §4.3).
 *
 * On the test phone a VPN that forbids bypassing let every connection on a Wi-Fi-bound socket
 * hang, while the default route reached the server. Neither path can be chosen in advance,
 * so every local address has to be raced both ways and the transfer has to follow the winner.
 */
class FilumHttpClientsTest {

    private val tls = FilumTls.bundle("A".repeat(43), keyManagers = null)

    private val lan = ServerEndpoint("192.168.1.10", 8765, EndpointKind.LAN)
    private val mdns = ServerEndpoint("192.168.1.10", 8765, EndpointKind.MDNS)
    private val overlay = ServerEndpoint("100.64.10.20", 8765, EndpointKind.OVERLAY)

    /** A distinct factory, so a test can tell whether a client was built on it. */
    private val wifiFactory = object : SocketFactory() {
        private val system = getDefault()
        override fun createSocket(): Socket = system.createSocket()
        override fun createSocket(host: String, port: Int): Socket = system.createSocket(host, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            system.createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = system.createSocket(host, port)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            system.createSocket(address, port, localAddress, localPort)
    }

    private val withWifi = FilumHttpClients(tls, binder = { wifiFactory })
    private val withoutWifi = FilumHttpClients(tls, binder = { null })

    @Test
    fun `every local address is raced bound and on the default route`() {
        val routes = withWifi.routesFor(listOf(lan, mdns, overlay))

        assertEquals(
            listOf(lan, mdns, overlay, lan.copy(boundToWifi = true), mdns.copy(boundToWifi = true)),
            routes,
        )
    }

    @Test
    fun `an overlay address is never bound`() {
        // The overlay is reached through its own tunnel; binding it to Wi-Fi would cut it off.
        assertEquals(listOf(overlay), withWifi.routesFor(listOf(overlay)))
    }

    @Test
    fun `without a known Wi-Fi network nothing is bound`() {
        assertEquals(listOf(lan, overlay), withoutWifi.routesFor(listOf(lan, overlay)))
    }

    @Test
    fun `a bound route gets the Wi-Fi socket and an unbound one the default route`() {
        assertSame(wifiFactory, withWifi.forEndpoint(lan.copy(boundToWifi = true)).socketFactory)
        assertNotSame(wifiFactory, withWifi.forEndpoint(lan).socketFactory)
        assertNotSame(wifiFactory, withWifi.forEndpoint(overlay).socketFactory)
    }

    @Test
    fun `the probe client keeps the socket of its route`() {
        assertSame(wifiFactory, withWifi.forProbe(lan.copy(boundToWifi = true)).socketFactory)
        assertNotSame(wifiFactory, withWifi.forProbe(lan).socketFactory)
    }

    @Test
    fun `a bound route falls back to the default route when Wi-Fi is gone`() {
        val client = withoutWifi.forEndpoint(lan.copy(boundToWifi = true))

        assertSame(withoutWifi.defaultRoute, client)
    }

    @Test
    fun `the journal tells which way a route went`() {
        assertEquals("192.168.1.10:8765 (lan)", lan.toString())
        assertEquals("192.168.1.10:8765 (lan, wifi-bound)", lan.copy(boundToWifi = true).toString())
    }
}
