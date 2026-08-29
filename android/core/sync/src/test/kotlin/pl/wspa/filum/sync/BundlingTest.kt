package pl.wspa.filum.sync

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.protocol.BundleFileResult
import pl.wspa.filum.protocol.BundleResponse
import pl.wspa.filum.protocol.ErrorResponse
import pl.wspa.filum.protocol.HttpStatus
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.protocol.ManifestResponse
import pl.wspa.filum.protocol.NeededFile
import pl.wspa.filum.scan.ScannedFile
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * Grupowanie małych plików w paczki (§5.4 protokołu, ADR-008).
 *
 * Sedno tej optymalizacji to podział: co idzie paczką, co pojedynczo i co się dzieje,
 * gdy paczka częściowo zawiedzie. Pomyłka w którymkolwiek z tych miejsc albo gubi
 * pliki, albo wysyła je w kółko.
 */
class BundlingTest {

    private val mtime = 1_756_400_000L
    private val nigdy = Deadline { false }

    private lateinit var queue: FakeQueue
    private lateinit var journal: FakeJournal
    private lateinit var transport: FakeTransport

    private fun setUp() {
        queue = FakeQueue()
        journal = FakeJournal()
        transport = FakeTransport()
    }

    private fun plik(dir: Path, name: String, bytes: Int): ScannedFile {
        val file = File(dir.toFile(), name)
        file.parentFile?.mkdirs()
        file.writeText("x".repeat(bytes))
        file.setLastModified(mtime * 1000)
        return ScannedFile(name, file.absolutePath, bytes.toLong(), mtime)
    }

    private suspend fun run(files: List<ScannedFile>, config: SyncConfig): SyncOutcome =
        SyncEngine(transport, queue, journal, config)
            .run("sesja", ManifestRequest.MODE_DELTA, files.asSequence(), nigdy)

    // --- podział na paczki i pojedyncze żądania ---

    @Test
    fun `male pliki ida paczka, duze pojedynczo`(@TempDir dir: Path) = runTest {
        setUp()
        val male = (1..5).map { plik(dir, "maly$it.txt", 100) }
        val duzy = plik(dir, "duzy.bin", 5_000)

        val outcome = run(male + duzy, SyncConfig(smallFileThreshold = 1_000))

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals(1, transport.bundles.size, "pięć małych plików to jedna paczka")
        assertEquals(5, transport.bundles.single().size)
        assertEquals(listOf("duzy.bin"), transport.putCalls.map { it.first })
    }

    @Test
    fun `paczka pilnuje limitu rozmiaru`(@TempDir dir: Path) = runTest {
        setUp()
        val pliki = (1..6).map { plik(dir, "p$it.txt", 400) }

        run(pliki, SyncConfig(smallFileThreshold = 1_000, bundleMaxBytes = 1_000))

        // 400 B na plik przy limicie 1000 B daje najwyżej dwa pliki na paczkę.
        assertEquals(3, transport.bundles.size)
        assertTrue(transport.bundles.all { it.size <= 2 }, "${transport.bundles}")
    }

    @Test
    fun `paczka pilnuje limitu liczby plikow`(@TempDir dir: Path) = runTest {
        setUp()
        val pliki = (1..7).map { plik(dir, "p$it.txt", 10) }

        run(pliki, SyncConfig(smallFileThreshold = 1_000, bundleMaxCount = 3))

        assertEquals(3, transport.bundles.size)
        assertEquals(listOf(3, 3, 1), transport.bundles.map { it.size })
    }

    @Test
    fun `wznawiany plik nigdy nie trafia do paczki`(@TempDir dir: Path) = runTest {
        // Paczka wysyła plik w całości od zera. Wrzucenie do niej pliku, którego część
        // serwer już ma, nadpisałoby postęp i zmarnowało dotychczasowy transfer.
        setUp()
        val plik = plik(dir, "przerwany.bin", 500)
        transport.received["przerwany.bin"] = StringBuilder("x".repeat(200))
        transport.manifestResponder = {
            ApiResult.Ok(ManifestResponse(0, listOf(NeededFile("przerwany.bin", 200))))
        }

        run(listOf(plik), SyncConfig(smallFileThreshold = 1_000))

        assertTrue(transport.bundles.isEmpty(), "plik ze wznowieniem musi pójść przez PUT")
        assertEquals(listOf(Triple("przerwany.bin", 200L, 300L)), transport.putCalls)
    }

    @Test
    fun `zawartosc plikow w paczce dociera nienaruszona`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "a.txt", 10)
        val b = plik(dir, "b.txt", 3_000)

        run(listOf(a, b), SyncConfig(smallFileThreshold = 10_000))

        assertEquals("x".repeat(10), transport.content("a.txt"))
        assertEquals("x".repeat(3_000), transport.content("b.txt"))
    }

    // --- częściowe niepowodzenie paczki ---

    @Test
    fun `odrzucona sciezka w paczce jest bledem trwalym`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "zly.txt", 10)
        val b = plik(dir, "dobry.txt", 10)

        transport.bundleResponder = {
            ApiResult.Ok(BundleResponse(listOf(
                BundleFileResult("zly.txt", ok = false, error = "traversal"),
                BundleFileResult("dobry.txt", ok = true),
            )))
        }

        run(listOf(a, b), SyncConfig(smallFileThreshold = 1_000))

        assertEquals(FakeQueue.State.PERMANENT, queue.state("zly.txt"))
        assertEquals(FakeQueue.State.DONE, queue.state("dobry.txt"))
        assertTrue(journal.has("traversal"))
    }

    @Test
    fun `inny blad pojedynczego pliku zostawia go w kolejce`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "a.txt", 10)

        transport.bundleResponder = {
            ApiResult.Ok(BundleResponse(listOf(
                BundleFileResult("a.txt", ok = false, error = "digest_mismatch"),
            )))
        }

        run(listOf(a), SyncConfig(smallFileThreshold = 1_000))

        assertEquals(FakeQueue.State.TRANSIENT, queue.state("a.txt"))
    }

    @Test
    fun `plik pominiety w odpowiedzi wraca do kolejki`(@TempDir dir: Path) = runTest {
        // Serwer nie wspomniał o pliku, który wysłaliśmy. Uznanie tego za powodzenie
        // byłoby cichą utratą — plik zniknąłby z kolejki, nie trafiwszy na dysk.
        setUp()
        val a = plik(dir, "a.txt", 10)
        val b = plik(dir, "b.txt", 10)

        transport.bundleResponder = {
            ApiResult.Ok(BundleResponse(listOf(BundleFileResult("a.txt", ok = true))))
        }

        run(listOf(a, b), SyncConfig(smallFileThreshold = 1_000))

        assertEquals(FakeQueue.State.DONE, queue.state("a.txt"))
        assertEquals(FakeQueue.State.TRANSIENT, queue.state("b.txt"))
        assertEquals("brak wyniku w odpowiedzi", queue.error("b.txt"))
    }

    @Test
    fun `zerwane polaczenie zwraca do kolejki cala paczke`(@TempDir dir: Path) = runTest {
        // Nie wiemy, ile plików serwer zdążył zapisać. Ponowna wysyłka niczego nie psuje,
        // bo manifest i tak odsieje te, które już doszły.
        setUp()
        val pliki = (1..4).map { plik(dir, "p$it.txt", 10) }
        transport.bundleResponder = { ApiResult.Unreachable(IOException("zerwane")) }

        val outcome = run(pliki, SyncConfig(smallFileThreshold = 1_000))

        assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(InterruptReason.CONNECTION, (outcome as SyncOutcome.Interrupted).reason)
        assertEquals(4, queue.rows.values.count { it.state == FakeQueue.State.TRANSIENT })
    }

    @Test
    fun `odrzucona paczka nie gubi plikow`(@TempDir dir: Path) = runTest {
        setUp()
        val pliki = (1..3).map { plik(dir, "p$it.txt", 10) }
        transport.bundleResponder = {
            ApiResult.failure(HttpStatus.BAD_REQUEST, ErrorResponse("bad_request", "uszkodzone ramkowanie"))
        }

        run(pliki, SyncConfig(smallFileThreshold = 1_000))

        assertEquals(3, queue.rows.values.count { it.state == FakeQueue.State.TRANSIENT })
        assertTrue(journal.has("bad_request"))
    }

    @Test
    fun `plik usuniety przed spakowaniem nie przerywa paczki`(@TempDir dir: Path) = runTest {
        setUp()
        val znika = plik(dir, "znika.txt", 10)
        val zostaje = plik(dir, "zostaje.txt", 10)
        File(znika.absolutePath).delete()

        run(listOf(znika, zostaje), SyncConfig(smallFileThreshold = 1_000))

        assertEquals(FakeQueue.State.PERMANENT, queue.state("znika.txt"))
        assertEquals(FakeQueue.State.DONE, queue.state("zostaje.txt"))
        assertEquals(listOf(listOf("zostaje.txt")), transport.bundles)
    }
}
