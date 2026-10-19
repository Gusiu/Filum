package pl.wspa.filum.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.wspa.filum.protocol.DeviceInfo
import pl.wspa.filum.protocol.ErrorResponse
import pl.wspa.filum.protocol.PingResponse
import pl.wspa.filum.protocol.Protocol
import pl.wspa.filum.protocol.ServerInfo
import pl.wspa.filum.protocol.StorageInfo
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The route race (protocol §4.2, ADR-006).
 *
 * The tests run entirely on `runTest`'s virtual clock, so delays measured in seconds do
 * not lengthen the run - while still checking the real ordering of events rather than the
 * selection logic alone.
 */
class EndpointRacerTest {

    private val lan = ServerEndpoint("192.168.1.10", 8765, EndpointKind.LAN)
    private val overlay = ServerEndpoint("100.64.10.20", 8765, EndpointKind.OVERLAY)
    private val dead = ServerEndpoint("10.0.0.1", 8765, EndpointKind.LAN)

    private fun ping(protocol: Int = Protocol.VERSION) = PingResponse(
        protocol = protocol,
        server = ServerInfo("DESKTOP-PC", "1.0.0", "instance"),
        endpoints = listOf("192.168.1.10:8765"),
        storage = StorageInfo(freeBytes = 500_000_000_000, totalBytes = 1_000_000_000_000),
        device = DeviceInfo("Galaxy S24 Ultra", fileCount = 0, bytesStored = 0),
    )

    @Test
    fun `the route that answered fastest wins`() = runTest {
        val racer = EndpointRacer(probe = { endpoint ->
            when (endpoint) {
                overlay -> { delay(500); ApiResult.Ok(ping()) }
                lan -> { delay(20); ApiResult.Ok(ping()) }
                else -> ApiResult.Unreachable(IOException("no route"))
            }
        })

        val outcome = racer.fastest(listOf(overlay, lan, dead))

        val reached = assertInstanceOf(RaceOutcome.Reached::class.java, outcome)
        assertEquals(lan, reached.endpoint)
    }

    @Test
    fun `a dead route does not delay the winner`() = runTest {
        // This is the whole point of the race: a stale address among the candidates costs
        // nothing, because the probes run in parallel. Without it, a router change would
        // mean waiting out a timeout on every sync.
        val racer = EndpointRacer(probe = { endpoint ->
            if (endpoint == overlay) { delay(30); ApiResult.Ok(ping()) }
            else { delay(100_000); ApiResult.Ok(ping()) }
        })

        val outcome = racer.fastest(listOf(dead, overlay))

        assertInstanceOf(RaceOutcome.Reached::class.java, outcome)
        assertEquals(overlay, (outcome as RaceOutcome.Reached).endpoint)
    }

    @Test
    fun `probing of the losing routes is cancelled`() {
        // Without cancellation the phone would hold connections it will never use -
        // during overnight work on battery that is no small thing.
        val finished = AtomicInteger()

        runTest {
            val racer = EndpointRacer(probe = { endpoint ->
                if (endpoint == lan) {
                    ApiResult.Ok(ping())
                } else {
                    delay(60_000)
                    finished.incrementAndGet()
                    ApiResult.Ok(ping())
                }
            })

            racer.fastest(listOf(lan, overlay, dead))
        }

        assertEquals(0, finished.get())
    }

    @Test
    fun `a probe slow to honour cancellation does not cost the winner its result`() = runBlocking {
        // A probe stuck in blocking I/O on a dead address notices cancellation only when
        // its own timeout runs out, which can be after the race deadline. Real threads
        // and real time on purpose: a virtual-time delay() is always cancelled at once
        // and would hide exactly this case. The winner answers only once the dead probe is
        // blocked, as on a real network, where a connect to a dead address starts at once.
        val deadProbeBlocked = CompletableDeferred<Unit>()
        val racer = EndpointRacer(
            probe = { endpoint ->
                if (endpoint == lan) {
                    deadProbeBlocked.await()
                    ApiResult.Ok(ping())
                } else {
                    withContext(Dispatchers.IO) {
                        deadProbeBlocked.complete(Unit)
                        Thread.sleep(600)
                        ApiResult.Unreachable(IOException("connect timed out"))
                    }
                }
            },
            timeoutMillis = 300,
        )

        val outcome = racer.fastest(listOf(lan, dead))

        val reached = assertInstanceOf(RaceOutcome.Reached::class.java, outcome)
        assertEquals(lan, reached.endpoint)
        assertTrue(reached.elapsedMillis < 300, "elapsed ${reached.elapsedMillis} ms")
    }

    @Test
    fun `when no route answers we return the reasons`() = runTest {
        val racer = EndpointRacer(probe = { ApiResult.Unreachable(IOException("connection refused")) })

        val outcome = racer.fastest(listOf(lan, overlay))

        val failed = assertInstanceOf(RaceOutcome.AllFailed::class.java, outcome)
        assertEquals(2, failed.failures.size)
        assertTrue(failed.failures.all { it.reason.contains("refused") })
    }

    @Test
    fun `exceeding the timeout ends the race`() = runTest {
        val racer = EndpointRacer(
            probe = { delay(10_000); ApiResult.Ok(ping()) },
            timeoutMillis = 2_000,
        )

        val outcome = racer.fastest(listOf(lan, overlay))

        val failed = assertInstanceOf(RaceOutcome.AllFailed::class.java, outcome)
        assertEquals(2, failed.failures.size)
        assertTrue(failed.failures.all { it.reason.contains("No answer") })
    }

    @Test
    fun `a server with an unsupported protocol version loses the route`() = runTest {
        // The route works, but at the other end is a Filum version we do not understand.
        // Another address may still reach the right instance, so this is the route
        // failing, not the whole race.
        val racer = EndpointRacer(probe = { endpoint ->
            if (endpoint == lan) ApiResult.Ok(ping(protocol = 99)) else ApiResult.Ok(ping())
        })

        val outcome = racer.fastest(listOf(lan, overlay))

        val reached = assertInstanceOf(RaceOutcome.Reached::class.java, outcome)
        assertEquals(overlay, reached.endpoint)
    }

    @Test
    fun `when every route has the wrong protocol version the race is lost`() = runTest {
        val racer = EndpointRacer(probe = { ApiResult.Ok(ping(protocol = 99)) })

        val outcome = racer.fastest(listOf(lan, overlay))

        val failed = assertInstanceOf(RaceOutcome.AllFailed::class.java, outcome)
        assertTrue(failed.failures.all { it.reason.contains("protocol 99") })
    }

    @Test
    fun `duplicate addresses are probed once`() = runTest {
        // The route set is merged from the pairing code, the cache and the ping response
        // (§4.4), so duplicates are the norm rather than the exception.
        val calls = AtomicInteger()
        val racer = EndpointRacer(probe = {
            calls.incrementAndGet()
            ApiResult.Unreachable(IOException("unreachable"))
        })

        racer.fastest(listOf(lan, lan, lan))

        assertEquals(1, calls.get())
    }

    @Test
    fun `an empty candidate set is its own outcome`() = runTest {
        assertEquals(RaceOutcome.NoCandidates, EndpointRacer(probe = { ApiResult.Ok(ping()) }).fastest(emptyList()))
    }

    // --- before pairing (§3.3) ---

    @Test
    fun `before pairing any answer through the pinned key proves the route`() = runTest {
        // Without a client certificate the server answers 401. The TLS handshake has already
        // checked its key, so even a refusal says the route leads to the right server.
        val racer = EndpointRacer(probe = { endpoint ->
            if (endpoint == lan) {
                delay(30)
                ApiResult.failure(401, ErrorResponse("unauthorized", "No client certificate."))
            } else {
                delay(10)
                ApiResult.Unreachable(IOException("handshake failed"))
            }
        })

        val outcome = racer.firstAnswering(listOf(dead, lan))

        assertEquals(lan, assertInstanceOf(Reachability.Answered::class.java, outcome).endpoint)
    }

    @Test
    fun `a dead address first in the pairing code costs no timeout`() = runTest {
        // Found on the test phone: pairing took 16 s, because the code listed the overlay
        // address first and the addresses were tried one after another.
        val racer = EndpointRacer(probe = { endpoint ->
            if (endpoint == overlay) {
                delay(10_000)
                ApiResult.Unreachable(IOException("timeout"))
            } else {
                delay(40)
                ApiResult.failure(401, null)
            }
        })

        val started = currentTime
        val outcome = racer.firstAnswering(listOf(overlay, lan))

        assertInstanceOf(Reachability.Answered::class.java, outcome)
        assertEquals(40, currentTime - started)
    }

    @Test
    fun `when no address answers before pairing we return the reasons`() = runTest {
        val racer = EndpointRacer(probe = { ApiResult.Unreachable(IOException("connection refused")) })

        val outcome = racer.firstAnswering(listOf(lan, overlay))

        val silent = assertInstanceOf(Reachability.Silent::class.java, outcome)
        assertEquals(2, silent.failures.size)
        assertTrue(silent.reasons().all { it.contains("connection refused") })
    }
}

class ServerEndpointTest {

    @Test
    fun `parsing an address with a port`() {
        val endpoint = ServerEndpoint.parse("192.168.1.10:8765", EndpointKind.LAN)

        assertEquals(ServerEndpoint("192.168.1.10", 8765, EndpointKind.LAN), endpoint)
        assertEquals("https://192.168.1.10:8765", endpoint!!.baseUrl)
    }

    @Test
    fun `an address without a port gets the default port`() {
        assertEquals(8765, ServerEndpoint.parse("192.168.1.10", EndpointKind.LAN)!!.port)
    }

    @Test
    fun `an IPv6 address in brackets`() {
        val endpoint = ServerEndpoint.parse("[fe80::1]:9000", EndpointKind.OVERLAY)

        assertEquals("fe80::1", endpoint!!.host)
        assertEquals(9000, endpoint.port)
        assertEquals("https://[fe80::1]:9000", endpoint.baseUrl)
    }

    @Test
    fun `a bare IPv6 address with no port`() {
        val endpoint = ServerEndpoint.parse("fe80::1", EndpointKind.OVERLAY)

        assertEquals("fe80::1", endpoint!!.host)
        assertEquals(8765, endpoint.port)
    }

    @Test
    fun `a corrupt entry yields null rather than an exception`() {
        // The address list arrives off the network (§4.4); one broken entry must not
        // wreck the whole route refresh.
        assertEquals(null, ServerEndpoint.parse("", EndpointKind.LAN))
        assertEquals(null, ServerEndpoint.parse("   ", EndpointKind.LAN))
        assertEquals(null, ServerEndpoint.parse("host:0", EndpointKind.LAN))
        assertEquals(null, ServerEndpoint.parse("host:99999", EndpointKind.LAN))
        assertEquals(null, ServerEndpoint.parse("[unclosed", EndpointKind.LAN))
    }

    @Test
    fun `the route kind decides whether the socket is bound`() {
        assertTrue(EndpointKind.LAN.isLocal)
        assertTrue(EndpointKind.MDNS.isLocal)
        assertTrue(!EndpointKind.OVERLAY.isLocal)
        assertTrue(!EndpointKind.MANUAL.isLocal)
    }

    @Test
    fun `the kind of a reported address follows its range`() {
        // The pairing code lists every interface of the computer. Stored as LAN, the
        // overlay address was bound to Wi-Fi as well, where it can never answer.
        assertEquals(EndpointKind.LAN, EndpointKind.of("192.168.1.9"))
        assertEquals(EndpointKind.LAN, EndpointKind.of("10.0.0.5"))
        assertEquals(EndpointKind.LAN, EndpointKind.of("172.30.176.1"))
        assertEquals(EndpointKind.LAN, EndpointKind.of("169.254.10.1"))
        assertEquals(EndpointKind.OVERLAY, EndpointKind.of("100.126.252.158"))
        assertEquals(EndpointKind.LAN, EndpointKind.of("fe80::1"))
        assertEquals(EndpointKind.LAN, EndpointKind.of("fd12:3456::1"))
        assertEquals(EndpointKind.OVERLAY, EndpointKind.of("fd7a:115c:a1e0::1"))
    }

    @Test
    fun `an address outside the known ranges is not taken for local`() {
        assertEquals(EndpointKind.MANUAL, EndpointKind.of("100.63.255.1"))
        assertEquals(EndpointKind.MANUAL, EndpointKind.of("172.32.0.1"))
        assertEquals(EndpointKind.MANUAL, EndpointKind.of("8.8.8.8"))
        assertEquals(EndpointKind.MANUAL, EndpointKind.of("2001:db8::1"))
        assertEquals(EndpointKind.MANUAL, EndpointKind.of("filum.example.org"))
        assertEquals(EndpointKind.MANUAL, EndpointKind.of("192.168.1.300"))
    }

    @Test
    fun `classifying keeps the address and port`() {
        val parsed = ServerEndpoint.parse("100.126.252.158:8765", EndpointKind.MANUAL)!!.classified()

        assertEquals(ServerEndpoint("100.126.252.158", 8765, EndpointKind.OVERLAY), parsed)
    }
}
