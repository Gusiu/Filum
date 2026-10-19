package pl.wspa.filum.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.wspa.filum.data.EndpointEntity
import pl.wspa.filum.net.EndpointKind
import pl.wspa.filum.net.ServerEndpoint

/**
 * Merging the routes the server reports into the stored ones (protocol §4.4).
 *
 * Found on the test phone: after dozens of successful cycles every stored route still had no
 * record of ever working, because each cycle saved the reported routes as fresh rows.
 */
class RouteBookTest {

    private val home = EndpointEntity("192.168.1.9", 8765, "LAN", lastOkAt = 1_000, lastRttMillis = 580)

    @Test
    fun `a known route keeps its history`() {
        val merged = RouteBook.merge(listOf(home), listOf(ServerEndpoint("192.168.1.9", 8765, EndpointKind.LAN)))

        assertEquals(listOf(home), merged)
    }

    @Test
    fun `a known route takes the kind it is found with`() {
        // Every address from the pairing code used to be stored as LAN, the overlay one too.
        val meshnet = EndpointEntity("100.126.252.158", 8765, "LAN", lastFailureAt = 2_000)

        val merged = RouteBook.merge(
            listOf(meshnet),
            listOf(ServerEndpoint("100.126.252.158", 8765, EndpointKind.OVERLAY)),
        )

        assertEquals(listOf(meshnet.copy(kind = "OVERLAY")), merged)
    }

    @Test
    fun `a new route starts with no history`() {
        val merged = RouteBook.merge(listOf(home), listOf(ServerEndpoint("192.168.50.2", 8765, EndpointKind.LAN)))

        assertEquals(listOf(EndpointEntity("192.168.50.2", 8765, "LAN")), merged)
    }

    @Test
    fun `routes not found now are left alone`() {
        // Merging returns only what to save; a route missing from one ping response is
        // not forgotten - it may be the overlay address while the phone is at home.
        val merged = RouteBook.merge(listOf(home), emptyList())

        assertEquals(emptyList<EndpointEntity>(), merged)
    }

    @Test
    fun `the same address found twice is saved once`() {
        val route = ServerEndpoint("192.168.1.9", 8765, EndpointKind.LAN)

        val merged = RouteBook.merge(emptyList(), listOf(route, route.copy(boundToWifi = true)))

        assertEquals(1, merged.size)
    }
}
