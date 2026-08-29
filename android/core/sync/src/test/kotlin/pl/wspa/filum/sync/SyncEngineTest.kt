package pl.wspa.filum.sync

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.protocol.ErrorResponse
import pl.wspa.filum.protocol.FileChunkResponse
import pl.wspa.filum.protocol.HttpStatus
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.protocol.ManifestResponse
import pl.wspa.filum.protocol.NeededFile
import pl.wspa.filum.scan.ScannedFile
import java.io.File
import java.nio.file.Path

/**
 * Silnik jednego cyklu synchronizacji.
 *
 * To jest najważniejszy zestaw testów w całym kliencie: tutaj zapadają decyzje
 * o tym, co ponowić, co odłożyć na trwałe i kiedy skończyć — a każda z nich
 * kończy się albo utratą pliku, albo wysyłaniem go w kółko.
 */
class SyncEngineTest {

    private val mtime = 1_756_400_000L
    private val nigdy = Deadline { false }
    private val natychmiast = Deadline { true }

    private lateinit var queue: FakeQueue
    private lateinit var journal: FakeJournal
    private lateinit var transport: FakeTransport

    private fun engine(config: SyncConfig = SyncConfig()) =
        SyncEngine(transport, queue, journal, config)

    private fun setUp() {
        queue = FakeQueue()
        journal = FakeJournal()
        transport = FakeTransport()
    }

    private fun plik(dir: Path, name: String, content: String): ScannedFile {
        val file = File(dir.toFile(), name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.setLastModified(mtime * 1000)
        return ScannedFile(name, file.absolutePath, content.length.toLong(), mtime)
    }

    private suspend fun run(
        files: List<ScannedFile>,
        deadline: Deadline = nigdy,
        config: SyncConfig = SyncConfig(),
    ): SyncOutcome = engine(config).run("sesja-1", ManifestRequest.MODE_DELTA, files.asSequence(), deadline)

    // --- ścieżka podstawowa ---

    @Test
    fun `pliki potrzebne serwerowi trafiaja na serwer w calosci`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "DCIM/a.jpg", "zawartosc-a")
        val b = plik(dir, "Documents/b.txt", "zawartosc-b-dluzsza")

        val outcome = run(listOf(a, b))

        val completed = assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals(2, completed.filesSent)
        assertEquals("zawartosc-a", transport.content("DCIM/a.jpg"))
        assertEquals("zawartosc-b-dluzsza", transport.content("Documents/b.txt"))
        assertEquals(FakeQueue.State.DONE, queue.state("DCIM/a.jpg"))
    }

    @Test
    fun `plik ktory serwer juz ma nie jest wysylany`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "DCIM/a.jpg", "aaa")
        val b = plik(dir, "DCIM/b.jpg", "bbb")

        // Serwer zgłasza tylko b jako potrzebne. Pliku a nie ma ani w "needed",
        // ani w "rejected", więc serwer go ma — i nie wolno go wysyłać ponownie.
        transport.manifestResponder = { ApiResult.Ok(ManifestResponse(1, listOf(NeededFile("DCIM/b.jpg", 0)))) }

        val outcome = run(listOf(a, b))

        assertEquals(1, (outcome as SyncOutcome.Completed).filesSent)
        assertEquals(FakeQueue.State.DONE, queue.state("DCIM/a.jpg"))
        assertEquals("", transport.content("DCIM/a.jpg"))
        assertEquals("bbb", transport.content("DCIM/b.jpg"))
    }

    @Test
    fun `manifest idzie partiami z poprawnym znacznikiem ostatniej`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..5).map { plik(dir, "plik$it.txt", "x") }

        run(files, config = SyncConfig(manifestBatch = 2))

        assertEquals(3, transport.requests.size)
        assertEquals(listOf(2, 2, 1), transport.requests.map { it.files.size })
        assertEquals(listOf(0, 1, 2), transport.requests.map { it.batch })
        assertEquals(listOf(false, false, true), transport.requests.map { it.final })
    }

    // --- wznawianie ---

    @Test
    fun `wznowienie zaczyna od przesuniecia podanego przez serwer`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "klip.mp4", "0123456789ABCDEF")

        // Serwer ma już pierwsze 6 bajtów z przerwanego wcześniej transferu.
        transport.received["klip.mp4"] = StringBuilder("012345")
        transport.manifestResponder = { ApiResult.Ok(ManifestResponse(0, listOf(NeededFile("klip.mp4", 6)))) }

        run(listOf(a))

        assertEquals("0123456789ABCDEF", transport.content("klip.mp4"))
        // Kluczowe: wysłaliśmy wyłącznie brakujące 10 bajtów, nie cały plik od nowa.
        assertEquals(listOf(Triple("klip.mp4", 6L, 10L)), transport.putCalls)
    }

    @Test
    fun `konflikt przesuniecia jest naprawiany wartoscia od serwera`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "klip.mp4", "0123456789")

        // Nasza kolejka twierdzi, że serwer ma 8 bajtów, a on ma 4.
        transport.received["klip.mp4"] = StringBuilder("0123")
        transport.manifestResponder = { ApiResult.Ok(ManifestResponse(0, listOf(NeededFile("klip.mp4", 8)))) }

        val outcome = run(listOf(a))

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals("0123456789", transport.content("klip.mp4"))
        assertTrue(journal.has("offset_corrected"))
    }

    @Test
    fun `krazacy konflikt przesuniecia nie zapetla sie`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "klip.mp4", "0123456789")

        // Serwer za każdym razem podaje inną wartość — coś się po drugiej stronie
        // zmienia w trakcie. Bez limitu ta pętla nigdy by się nie skończyła.
        var kolejne = 0L
        transport.putInterceptor = { _, _, _ ->
            kolejne = (kolejne + 1) % 5
            ApiResult.failure(
                HttpStatus.CONFLICT, ErrorResponse("offset_mismatch", "wciąż nie tak", kolejne),
            )
        }

        val outcome = run(listOf(a))

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals(FakeQueue.State.TRANSIENT, queue.state("klip.mp4"))
        assertEquals("niestabilne przesunięcie", queue.error("klip.mp4"))
    }

    @Test
    fun `duzy plik idzie fragmentami`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "duzy.bin", "x".repeat(100))

        run(listOf(a), config = SyncConfig(chunkBytes = 30))

        assertEquals(4, transport.putCalls.size)
        assertEquals(listOf(0L, 30L, 60L, 90L), transport.putCalls.map { it.second })
        assertEquals(listOf(30L, 30L, 30L, 10L), transport.putCalls.map { it.third })
        assertEquals("x".repeat(100), transport.content("duzy.bin"))
    }

    // --- skrót kontrolny (§9) ---

    @Test
    fun `maly plik dostaje skrot na ostatnim fragmencie`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "maly.txt", "abc")

        run(listOf(a))

        assertNotNull(transport.digestsSeen["maly.txt"], "skrót powinien dojechać przy domknięciu")
        assertEquals(64, transport.digestsSeen["maly.txt"]!!.length)
    }

    @Test
    fun `duzy plik nie placi za dodatkowy odczyt`(@TempDir dir: Path) = runTest {
        // Skrót wymaga przeczytania całego pliku przed wysłaniem, bo nagłówek powstaje
        // przed ciałem. Dla wielogigabajtowego nagrania podwoiłoby to operacje dyskowe.
        setUp()
        val a = plik(dir, "duzy.bin", "x".repeat(100))

        run(listOf(a), config = SyncConfig(digestMaxBytes = 10))

        assertNull(transport.digestsSeen["duzy.bin"])
    }

    @Test
    fun `wznawiany plik nie dostaje skrotu`(@TempDir dir: Path) = runTest {
        // Policzenie skrótu wymagałoby odczytu od początku, co przeczy sensowi wznawiania.
        setUp()
        val a = plik(dir, "klip.mp4", "0123456789")
        transport.received["klip.mp4"] = StringBuilder("01234")
        transport.manifestResponder = { ApiResult.Ok(ManifestResponse(0, listOf(NeededFile("klip.mp4", 5)))) }

        run(listOf(a))

        assertNull(transport.digestsSeen["klip.mp4"])
    }

    // --- błędy ---

    @Test
    fun `sciezka odrzucona w manifescie nie jest w ogole wysylana`(@TempDir dir: Path) = runTest {
        setUp()
        val zly = plik(dir, "zly.txt", "x")
        val dobry = plik(dir, "dobry.txt", "y")
        transport.manifestResponder = FakeTransport.manifestRejects("zly.txt")

        run(listOf(zly, dobry))

        assertEquals(FakeQueue.State.PERMANENT, queue.state("zly.txt"))
        assertEquals(0, transport.putCalls.count { it.first == "zly.txt" })
        assertTrue(journal.has("path_rejected"))
    }

    @Test
    fun `blad trwaly nie wraca do kolejki`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "a.txt", "x")
        transport.putInterceptor = { _, _, _ -> FakeTransport.rejected() }

        run(listOf(a))

        assertEquals(FakeQueue.State.PERMANENT, queue.state("a.txt"))
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun `blad przejsciowy zostawia plik w kolejce`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "a.txt", "x")
        transport.putInterceptor = { _, _, _ -> FakeTransport.outOfSpace() }

        run(listOf(a))

        assertEquals(FakeQueue.State.TRANSIENT, queue.state("a.txt"))
        assertTrue(journal.has("insufficient_storage"))
    }

    @Test
    fun `zerwane polaczenie konczy cykl, a nie plik`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "a.txt", "x")
        val b = plik(dir, "b.txt", "y")
        transport.putInterceptor = { _, path, _ ->
            if (path == "a.txt") FakeTransport.unreachable() else null
        }

        val outcome = run(listOf(a, b))

        val interrupted = assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(InterruptReason.CONNECTION, interrupted.reason)
        assertEquals(FakeQueue.State.TRANSIENT, queue.state("a.txt"))
    }

    @Test
    fun `nieosiagalny serwer przy manifescie konczy cykl niepowodzeniem`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "a.txt", "x")
        transport.manifestResponder = { ApiResult.Unreachable(java.io.IOException("brak trasy")) }

        val outcome = run(listOf(a))

        assertInstanceOf(SyncOutcome.Failed::class.java, outcome)
        assertTrue(journal.has("manifest_unreachable"))
    }

    @Test
    fun `plik usuniety po skanie jest odkladany bez bledu`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "znika.txt", "x")
        File(a.absolutePath).delete()

        val outcome = run(listOf(a))

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals(FakeQueue.State.PERMANENT, queue.state("znika.txt"))
        assertEquals("plik już nie istnieje", queue.error("znika.txt"))
    }

    @Test
    fun `plik zmieniony po skanie nie jest sklejany z dwoch wersji`(@TempDir dir: Path) = runTest {
        // Wysłanie go teraz dałoby na serwerze plik, który nigdy nie istniał.
        setUp()
        val a = plik(dir, "rosnie.txt", "krotki")
        File(a.absolutePath).writeText("znacznie dluzsza zawartosc")
        File(a.absolutePath).setLastModified(mtime * 1000)

        run(listOf(a))

        assertEquals(FakeQueue.State.TRANSIENT, queue.state("rosnie.txt"))
        assertTrue(journal.has("file_changed"))
        assertEquals("", transport.content("rosnie.txt"))
    }

    @Test
    fun `plik po wielu nieudanych podejsciach przestaje wracac`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "uparty.txt", "x")
        queue.enqueue(listOf(a))
        queue.rows["uparty.txt"]!!.attempts = 5

        run(listOf(a), config = SyncConfig(maxAttempts = 5))

        assertEquals(FakeQueue.State.PERMANENT, queue.state("uparty.txt"))
        assertTrue(journal.has("too_many_attempts"))
    }

    // --- budżet ---

    @Test
    fun `wyczerpany budzet konczy cykl czysto przed wysylka`(@TempDir dir: Path) = runTest {
        setUp()
        val a = plik(dir, "a.txt", "x")

        val outcome = run(listOf(a), deadline = natychmiast)

        val interrupted = assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(InterruptReason.BUDGET, interrupted.reason)
    }

    @Test
    fun `budzet wyczerpany w polowie kolejki zachowuje postep`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..5).map { plik(dir, "plik$it.txt", "zawartosc$it") }

        var wyslane = 0
        val deadline = Deadline { wyslane >= 2 }
        transport.putInterceptor = { _, _, _ -> wyslane++; null }

        val outcome = run(files, deadline = deadline)

        val interrupted = assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(2, interrupted.filesSent)
        // Reszta czeka w kolejce; następny cykl podejmie ją bez ponownego skanu.
        assertEquals(3, queue.pendingCount())
        assertTrue(journal.has("deadline_upload"))
    }

    @Test
    fun `pusty skan konczy sie powodzeniem, a nie bledem`() = runTest {
        setUp()

        val outcome = run(emptyList())

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertTrue(journal.has("manifest_empty"))
        assertEquals(0, transport.requests.size)
    }
}
