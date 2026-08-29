package pl.wspa.filum.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.wspa.filum.protocol.DeviceInfo
import pl.wspa.filum.protocol.PingResponse
import pl.wspa.filum.protocol.Protocol
import pl.wspa.filum.protocol.ServerInfo
import pl.wspa.filum.protocol.StorageInfo
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wyścig tras (§4.2 protokołu, ADR-006).
 *
 * Testy są w całości sterowane zegarem wirtualnym `runTest`, więc opóźnienia liczone
 * w sekundach nie wydłużają wykonania — a jednocześnie sprawdzamy realną kolejność
 * zdarzeń, a nie samą logikę wyboru.
 */
class EndpointRacerTest {

    private val lan = ServerEndpoint("192.168.1.10", 8765, EndpointKind.LAN)
    private val overlay = ServerEndpoint("100.64.10.20", 8765, EndpointKind.OVERLAY)
    private val martwy = ServerEndpoint("10.0.0.1", 8765, EndpointKind.LAN)

    private fun ping(protocol: Int = Protocol.VERSION) = PingResponse(
        protocol = protocol,
        server = ServerInfo("DESKTOP-PC", "1.0.0", "instancja"),
        endpoints = listOf("192.168.1.10:8765"),
        storage = StorageInfo(freeBytes = 500_000_000_000, totalBytes = 1_000_000_000_000),
        device = DeviceInfo("Galaxy S24 Ultra", fileCount = 0, bytesStored = 0),
    )

    @Test
    fun `wygrywa trasa ktora odpowiedziala najszybciej`() = runTest {
        val racer = EndpointRacer(probe = { endpoint ->
            when (endpoint) {
                overlay -> { delay(500); ApiResult.Ok(ping()) }
                lan -> { delay(20); ApiResult.Ok(ping()) }
                else -> ApiResult.Unreachable(IOException("brak trasy"))
            }
        })

        val outcome = racer.fastest(listOf(overlay, lan, martwy))

        val reached = assertInstanceOf(RaceOutcome.Reached::class.java, outcome)
        assertEquals(lan, reached.endpoint)
    }

    @Test
    fun `martwa trasa nie opoznia zwyciezcy`() = runTest {
        // To jest cała pointa wyścigu: nieaktualny adres w zbiorze kandydatów kosztuje
        // zero, bo sondowania idą równolegle. Bez tego zmiana routera oznaczałaby
        // czekanie na limit czasu przy każdej synchronizacji.
        val racer = EndpointRacer(probe = { endpoint ->
            if (endpoint == overlay) { delay(30); ApiResult.Ok(ping()) }
            else { delay(100_000); ApiResult.Ok(ping()) }
        })

        val outcome = racer.fastest(listOf(martwy, overlay))

        assertInstanceOf(RaceOutcome.Reached::class.java, outcome)
        assertEquals(overlay, (outcome as RaceOutcome.Reached).endpoint)
    }

    @Test
    fun `sondowanie przegranych tras jest anulowane`() {
        // Bez anulowania telefon utrzymywałby połączenia, których i tak nie użyje —
        // przy pracy nocnej na baterii to nie jest drobiazg.
        val zakonczone = AtomicInteger()

        runTest {
            val racer = EndpointRacer(probe = { endpoint ->
                if (endpoint == lan) {
                    ApiResult.Ok(ping())
                } else {
                    delay(60_000)
                    zakonczone.incrementAndGet()
                    ApiResult.Ok(ping())
                }
            })

            racer.fastest(listOf(lan, overlay, martwy))
        }

        assertEquals(0, zakonczone.get())
    }

    @Test
    fun `gdy zadna trasa nie odpowiada zwracamy powody`() = runTest {
        val racer = EndpointRacer(probe = { ApiResult.Unreachable(IOException("połączenie odrzucone")) })

        val outcome = racer.fastest(listOf(lan, overlay))

        val failed = assertInstanceOf(RaceOutcome.AllFailed::class.java, outcome)
        assertEquals(2, failed.failures.size)
        assertTrue(failed.failures.all { it.reason.contains("odrzucone") })
    }

    @Test
    fun `przekroczenie limitu czasu konczy wyscig`() = runTest {
        val racer = EndpointRacer(
            probe = { delay(10_000); ApiResult.Ok(ping()) },
            timeoutMillis = 2_000,
        )

        val outcome = racer.fastest(listOf(lan, overlay))

        val failed = assertInstanceOf(RaceOutcome.AllFailed::class.java, outcome)
        assertEquals(2, failed.failures.size)
        assertTrue(failed.failures.all { it.reason.contains("Brak odpowiedzi") })
    }

    @Test
    fun `serwer o nieobslugiwanej wersji protokolu przegrywa trase`() = runTest {
        // Trasa działa, ale po drugiej stronie stoi Filum w wersji, której nie rozumiemy.
        // Innym adresem możemy trafić na właściwą instancję, więc to porażka trasy,
        // a nie całego wyścigu.
        val racer = EndpointRacer(probe = { endpoint ->
            if (endpoint == lan) ApiResult.Ok(ping(protocol = 99)) else ApiResult.Ok(ping())
        })

        val outcome = racer.fastest(listOf(lan, overlay))

        val reached = assertInstanceOf(RaceOutcome.Reached::class.java, outcome)
        assertEquals(overlay, reached.endpoint)
    }

    @Test
    fun `gdy wszystkie trasy maja zla wersje protokolu wyscig przepada`() = runTest {
        val racer = EndpointRacer(probe = { ApiResult.Ok(ping(protocol = 99)) })

        val outcome = racer.fastest(listOf(lan, overlay))

        val failed = assertInstanceOf(RaceOutcome.AllFailed::class.java, outcome)
        assertTrue(failed.failures.all { it.reason.contains("protokołem 99") })
    }

    @Test
    fun `powtorzone adresy sonduje sie raz`() = runTest {
        // Zbiór tras powstaje ze scalenia kodu parowania, pamięci podręcznej i odpowiedzi
        // ping (§4.4), więc duplikaty są normą, nie wyjątkiem.
        val wywolania = AtomicInteger()
        val racer = EndpointRacer(probe = {
            wywolania.incrementAndGet()
            ApiResult.Unreachable(IOException("brak"))
        })

        racer.fastest(listOf(lan, lan, lan))

        assertEquals(1, wywolania.get())
    }

    @Test
    fun `pusty zbior kandydatow jest osobnym wynikiem`() = runTest {
        assertEquals(RaceOutcome.NoCandidates, EndpointRacer(probe = { ApiResult.Ok(ping()) }).fastest(emptyList()))
    }
}

class ServerEndpointTest {

    @Test
    fun `odczyt adresu z portem`() {
        val endpoint = ServerEndpoint.parse("192.168.1.10:8765", EndpointKind.LAN)

        assertEquals(ServerEndpoint("192.168.1.10", 8765, EndpointKind.LAN), endpoint)
        assertEquals("https://192.168.1.10:8765", endpoint!!.baseUrl)
    }

    @Test
    fun `adres bez portu dostaje port domyslny`() {
        assertEquals(8765, ServerEndpoint.parse("192.168.1.10", EndpointKind.LAN)!!.port)
    }

    @Test
    fun `adres IPv6 w nawiasach`() {
        val endpoint = ServerEndpoint.parse("[fe80::1]:9000", EndpointKind.OVERLAY)

        assertEquals("fe80::1", endpoint!!.host)
        assertEquals(9000, endpoint.port)
        assertEquals("https://[fe80::1]:9000", endpoint.baseUrl)
    }

    @Test
    fun `goly adres IPv6 bez portu`() {
        val endpoint = ServerEndpoint.parse("fe80::1", EndpointKind.OVERLAY)

        assertEquals("fe80::1", endpoint!!.host)
        assertEquals(8765, endpoint.port)
    }

    @Test
    fun `zepsuty wpis daje null zamiast wyjatku`() {
        // Lista adresów przychodzi z sieci (§4.4); jeden zepsuty wpis nie może
        // wywrócić całego odświeżenia tras.
        assertEquals(null, ServerEndpoint.parse("", EndpointKind.LAN))
        assertEquals(null, ServerEndpoint.parse("   ", EndpointKind.LAN))
        assertEquals(null, ServerEndpoint.parse("host:0", EndpointKind.LAN))
        assertEquals(null, ServerEndpoint.parse("host:99999", EndpointKind.LAN))
        assertEquals(null, ServerEndpoint.parse("[niedomkniete", EndpointKind.LAN))
    }

    @Test
    fun `rodzaj trasy rozstrzyga o przypieciu gniazda`() {
        assertTrue(EndpointKind.LAN.isLocal)
        assertTrue(EndpointKind.MDNS.isLocal)
        assertTrue(!EndpointKind.OVERLAY.isLocal)
        assertTrue(!EndpointKind.MANUAL.isLocal)
    }
}
