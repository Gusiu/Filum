package pl.wspa.filum.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.scan.ScannedFile
import java.io.File
import java.nio.file.Path

/**
 * Several transfers in flight at once (SyncConfig.parallelTransfers).
 *
 * The transfers are held open with a delay in virtual time, so the overlap these tests
 * count is the one the engine allows, not an accident of thread scheduling.
 */
class ParallelTransfersTest {

    private val mtime = 1_756_400_000L

    private lateinit var queue: FakeQueue
    private lateinit var journal: FakeJournal
    private lateinit var transport: FakeTransport

    private fun setUp() {
        queue = FakeQueue()
        journal = FakeJournal()
        transport = FakeTransport()
    }

    private fun file(dir: Path, name: String, content: String): ScannedFile {
        val file = File(dir.toFile(), name)
        file.writeText(content)
        file.setLastModified(mtime * 1000)
        return ScannedFile(name, file.absolutePath, content.length.toLong(), mtime)
    }

    private suspend fun run(
        files: List<ScannedFile>,
        config: SyncConfig,
        deadline: Deadline = Deadline { false },
    ): SyncOutcome = SyncEngine(transport, queue, journal, config)
        .run("session", ManifestRequest.MODE_DELTA, files.asSequence(), deadline)

    /** Every file on its own request, so each one is a transfer. */
    private fun singles(parallel: Int) = SyncConfig(smallFileThreshold = 0, parallelTransfers = parallel)

    /** Holds every transfer open a while and returns how many were ever open at once. */
    private fun countOverlap(): () -> Int {
        var open = 0
        var most = 0
        transport.beforeUpload = {
            open++
            most = maxOf(most, open)
            delay(100)
            open--
        }
        return { most }
    }

    @Test
    fun `transfers overlap up to the configured number`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..7).map { file(dir, "f$it.bin", "x$it") }
        val most = countOverlap()

        val outcome = run(files, singles(parallel = 3))

        assertEquals(7, assertInstanceOf(SyncOutcome.Completed::class.java, outcome).filesSent)
        assertEquals(3, most())
    }

    @Test
    fun `by default one transfer runs at a time`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..4).map { file(dir, "f$it.bin", "x") }
        val most = countOverlap()

        run(files, SyncConfig(smallFileThreshold = 0))

        assertEquals(1, most())
    }

    @Test
    fun `a bundle counts as one transfer`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..6).map { file(dir, "s$it.txt", "x") }
        val most = countOverlap()

        run(files, SyncConfig(smallFileThreshold = 1_000, bundleMaxCount = 2, parallelTransfers = 3))

        assertEquals(3, transport.bundles.size)
        assertEquals(3, most())
    }

    @Test
    fun `a lost connection lets running transfers finish and starts no new one`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..5).map { file(dir, "f$it.bin", "x$it") }
        // f1 answers first, and with a dropped connection; f2 is still on its way.
        transport.beforeUpload = { path -> delay(if (path == "f1.bin") 10 else 100) }
        transport.putInterceptor = { _, path, _ -> if (path == "f1.bin") FakeTransport.unreachable() else null }

        val outcome = run(files, singles(parallel = 2))

        assertEquals(InterruptReason.CONNECTION, assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome).reason)
        assertEquals(FakeQueue.State.DONE, queue.state("f2.bin"), "a transfer already running is let finish")
        assertEquals(listOf("f1.bin", "f2.bin"), transport.putCalls.map { it.first }.distinct())
    }

    @Test
    fun `a budget that runs out starts no new transfer but lets running ones finish`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..5).map { file(dir, "f$it.bin", "x$it") }
        var started = 0
        transport.beforeUpload = {
            started++
            delay(50)
        }

        val outcome = run(files, singles(parallel = 2), deadline = Deadline { started >= 2 })

        val interrupted = assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(InterruptReason.BUDGET, interrupted.reason)
        assertEquals(2, interrupted.filesSent)
        assertEquals(3, queue.pendingCount())
        assertTrue(journal.has("deadline_upload"))
    }

    @Test
    fun `the number of transfers stays within what the HTTP client allows`() {
        assertThrows<IllegalArgumentException> { SyncConfig(parallelTransfers = 0) }
        assertThrows<IllegalArgumentException> {
            SyncConfig(parallelTransfers = SyncConfig.MAX_PARALLEL_TRANSFERS + 1)
        }
    }
}
