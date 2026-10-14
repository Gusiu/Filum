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
 * Grouping small files into bundles (protocol §5.4, ADR-008).
 *
 * The heart of this optimisation is the split: what goes in a bundle, what goes alone, and
 * what happens when a bundle partly fails. A mistake in any of those either loses files or
 * sends them over and over.
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

    private fun file(dir: Path, name: String, bytes: Int): ScannedFile {
        val file = File(dir.toFile(), name)
        file.parentFile?.mkdirs()
        file.writeText("x".repeat(bytes))
        file.setLastModified(mtime * 1000)
        return ScannedFile(name, file.absolutePath, bytes.toLong(), mtime)
    }

    private suspend fun run(files: List<ScannedFile>, config: SyncConfig): SyncOutcome =
        SyncEngine(transport, queue, journal, config)
            .run("sesja", ManifestRequest.MODE_DELTA, files.asSequence(), nigdy)

    // --- splitting into bundles and individual requests ---

    @Test
    fun `small files go in a bundle, large ones on their own`(@TempDir dir: Path) = runTest {
        setUp()
        val smallFiles = (1..5).map { file(dir, "small$it.txt", 100) }
        val large = file(dir, "large.bin", 5_000)

        val outcome = run(smallFiles + large, SyncConfig(smallFileThreshold = 1_000))

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals(1, transport.bundles.size, "five small files make one bundle")
        assertEquals(5, transport.bundles.single().size)
        assertEquals(listOf("large.bin"), transport.putCalls.map { it.first })
    }

    @Test
    fun `a bundle respects the size limit`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..6).map { file(dir, "p$it.txt", 400) }

        run(files, SyncConfig(smallFileThreshold = 1_000, bundleMaxBytes = 1_000))

        // 400 B per file against a 1000 B limit allows at most two files per bundle.
        assertEquals(3, transport.bundles.size)
        assertTrue(transport.bundles.all { it.size <= 2 }, "${transport.bundles}")
    }

    @Test
    fun `a bundle respects the file count limit`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..7).map { file(dir, "p$it.txt", 10) }

        run(files, SyncConfig(smallFileThreshold = 1_000, bundleMaxCount = 3))

        assertEquals(3, transport.bundles.size)
        assertEquals(listOf(3, 3, 1), transport.bundles.map { it.size })
    }

    @Test
    fun `a resumed file never goes into a bundle`(@TempDir dir: Path) = runTest {
        // A bundle sends a file whole, from zero. Putting a file the server already holds
        // part of into one would overwrite the progress and waste the transfer so far.
        setUp()
        val file = file(dir, "przerwany.bin", 500)
        transport.received["przerwany.bin"] = StringBuilder("x".repeat(200))
        transport.manifestResponder = {
            ApiResult.Ok(ManifestResponse(0, listOf(NeededFile("przerwany.bin", 200))))
        }

        run(listOf(file), SyncConfig(smallFileThreshold = 1_000))

        assertTrue(transport.bundles.isEmpty(), "a file with a resume point has to go by PUT")
        assertEquals(listOf(Triple("przerwany.bin", 200L, 300L)), transport.putCalls)
    }

    @Test
    fun `the content of files in a bundle arrives intact`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "a.txt", 10)
        val b = file(dir, "b.txt", 3_000)

        run(listOf(a, b), SyncConfig(smallFileThreshold = 10_000))

        assertEquals("x".repeat(10), transport.content("a.txt"))
        assertEquals("x".repeat(3_000), transport.content("b.txt"))
    }

    // --- partial bundle failure ---

    @Test
    fun `a rejected path inside a bundle is a permanent error`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "zly.txt", 10)
        val b = file(dir, "dobry.txt", 10)

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
    fun `another error on a single file leaves it in the queue`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "a.txt", 10)

        transport.bundleResponder = {
            ApiResult.Ok(BundleResponse(listOf(
                BundleFileResult("a.txt", ok = false, error = "digest_mismatch"),
            )))
        }

        run(listOf(a), SyncConfig(smallFileThreshold = 1_000))

        assertEquals(FakeQueue.State.TRANSIENT, queue.state("a.txt"))
    }

    @Test
    fun `a file omitted from the response returns to the queue`(@TempDir dir: Path) = runTest {
        // The server said nothing about a file we sent. Treating that as success would be
        // silent loss - the file would vanish from the queue without reaching the disk.
        setUp()
        val a = file(dir, "a.txt", 10)
        val b = file(dir, "b.txt", 10)

        transport.bundleResponder = {
            ApiResult.Ok(BundleResponse(listOf(BundleFileResult("a.txt", ok = true))))
        }

        run(listOf(a, b), SyncConfig(smallFileThreshold = 1_000))

        assertEquals(FakeQueue.State.DONE, queue.state("a.txt"))
        assertEquals(FakeQueue.State.TRANSIENT, queue.state("b.txt"))
        assertEquals("brak wyniku w odpowiedzi", queue.error("b.txt"))
    }

    @Test
    fun `a dropped connection returns the whole bundle to the queue`(@TempDir dir: Path) = runTest {
        // We do not know how many files the server managed to store. Re-sending breaks
        // nothing, because the manifest will sift out the ones that arrived.
        setUp()
        val files = (1..4).map { file(dir, "p$it.txt", 10) }
        transport.bundleResponder = { ApiResult.Unreachable(IOException("zerwane")) }

        val outcome = run(files, SyncConfig(smallFileThreshold = 1_000))

        assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(InterruptReason.CONNECTION, (outcome as SyncOutcome.Interrupted).reason)
        assertEquals(4, queue.rows.values.count { it.state == FakeQueue.State.TRANSIENT })
    }

    @Test
    fun `a rejected bundle loses no files`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..3).map { file(dir, "p$it.txt", 10) }
        transport.bundleResponder = {
            ApiResult.failure(HttpStatus.BAD_REQUEST, ErrorResponse("bad_request", "uszkodzone ramkowanie"))
        }

        run(files, SyncConfig(smallFileThreshold = 1_000))

        assertEquals(3, queue.rows.values.count { it.state == FakeQueue.State.TRANSIENT })
        assertTrue(journal.has("bad_request"))
    }

    @Test
    fun `a file deleted before bundling does not abort the bundle`(@TempDir dir: Path) = runTest {
        setUp()
        val vanishing = file(dir, "vanishing.txt", 10)
        val zostaje = file(dir, "zostaje.txt", 10)
        File(vanishing.absolutePath).delete()

        run(listOf(vanishing, zostaje), SyncConfig(smallFileThreshold = 1_000))

        assertEquals(FakeQueue.State.PERMANENT, queue.state("vanishing.txt"))
        assertEquals(FakeQueue.State.DONE, queue.state("zostaje.txt"))
        assertEquals(listOf(listOf("zostaje.txt")), transport.bundles)
    }
}
