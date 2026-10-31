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
import pl.wspa.filum.protocol.IntegrityPolicy
import pl.wspa.filum.protocol.ManifestResponse
import pl.wspa.filum.protocol.NeededFile
import pl.wspa.filum.scan.ScannedFile
import java.io.File
import java.nio.file.Path

/**
 * The engine of one sync cycle.
 *
 * This is the most important suite in the whole client: the decisions taken here are what
 * to retry, what to set aside permanently and when to stop - and each of them ends either
 * in losing a file or in sending it over and over.
 */
class SyncEngineTest {

    private val mtime = 1_756_400_000L
    private val noDeadline = Deadline { false }
    private val immediately = Deadline { true }

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

    private fun file(dir: Path, name: String, content: String): ScannedFile {
        val file = File(dir.toFile(), name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.setLastModified(mtime * 1000)
        return ScannedFile(name, file.absolutePath, content.length.toLong(), mtime)
    }

    /**
     * Runs a cycle with **bundling turned off**.
     *
     * This class covers the single-PUT path: resuming, offset conflicts, chunking and the
     * integrity digest. At the default threshold every test file would be too small for that
     * and would go in a bundle, so the threshold is zeroed explicitly.
     * The bundle path is covered by BundlingTest.
     */
    private suspend fun run(
        files: List<ScannedFile>,
        deadline: Deadline = noDeadline,
        config: SyncConfig = SyncConfig(),
    ): SyncOutcome = engine(config.copy(smallFileThreshold = 0))
        .run("session-1", ManifestRequest.MODE_DELTA, files.asSequence(), deadline)

    // --- the basic path ---

    @Test
    fun `the files the server needs arrive there intact`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "DCIM/a.jpg", "content-a")
        val b = file(dir, "Documents/b.txt", "content-b-longer")

        val outcome = run(listOf(a, b))

        val completed = assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals(2, completed.filesSent)
        assertEquals("content-a", transport.content("DCIM/a.jpg"))
        assertEquals("content-b-longer", transport.content("Documents/b.txt"))
        assertEquals(FakeQueue.State.DONE, queue.state("DCIM/a.jpg"))
    }

    @Test
    fun `a file the server already has is not sent`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "DCIM/a.jpg", "aaa")
        val b = file(dir, "DCIM/b.jpg", "bbb")

        // The server reports only b as needed. File a is in neither "needed" nor
        // "rejected", so the server has it - and it must not be sent again.
        transport.manifestResponder = { ApiResult.Ok(ManifestResponse(1, listOf(NeededFile("DCIM/b.jpg", 0)))) }

        val outcome = run(listOf(a, b))

        assertEquals(1, (outcome as SyncOutcome.Completed).filesSent)
        assertEquals(FakeQueue.State.DONE, queue.state("DCIM/a.jpg"))
        assertEquals("", transport.content("DCIM/a.jpg"))
        assertEquals("bbb", transport.content("DCIM/b.jpg"))
    }

    @Test
    fun `the manifest goes in batches with the final flag set correctly`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..5).map { file(dir, "file$it.txt", "x") }

        run(files, config = SyncConfig(manifestBatch = 2))

        assertEquals(3, transport.requests.size)
        assertEquals(listOf(2, 2, 1), transport.requests.map { it.files.size })
        assertEquals(listOf(0, 1, 2), transport.requests.map { it.batch })
        assertEquals(listOf(false, false, true), transport.requests.map { it.final })
    }

    // --- resuming ---

    @Test
    fun `resuming starts from the offset the server gave`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "clip.mp4", "0123456789ABCDEF")

        // The server already holds the first 6 bytes from an earlier interrupted transfer.
        transport.received["clip.mp4"] = StringBuilder("012345")
        transport.manifestResponder = { ApiResult.Ok(ManifestResponse(0, listOf(NeededFile("clip.mp4", 6)))) }

        run(listOf(a))

        assertEquals("0123456789ABCDEF", transport.content("clip.mp4"))
        // The crucial part: we sent only the missing 10 bytes, not the whole file again.
        assertEquals(listOf(Triple("clip.mp4", 6L, 10L)), transport.putCalls)
    }

    @Test
    fun `an offset conflict is repaired with the value from the server`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "clip.mp4", "0123456789")

        // Our queue claims the server has 8 bytes; it has 4.
        transport.received["clip.mp4"] = StringBuilder("0123")
        transport.manifestResponder = { ApiResult.Ok(ManifestResponse(0, listOf(NeededFile("clip.mp4", 8)))) }

        val outcome = run(listOf(a))

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals("0123456789", transport.content("clip.mp4"))
        assertTrue(journal.has("offset_corrected"))
    }

    @Test
    fun `a circling offset conflict does not loop forever`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "clip.mp4", "0123456789")

        // The server names a different value every time - something on the other side is
        // changing while we work. Without a limit this loop would never end.
        var nextOffset = 0L
        transport.putInterceptor = { _, _, _ ->
            nextOffset = (nextOffset + 1) % 5
            ApiResult.failure(
                HttpStatus.CONFLICT, ErrorResponse("offset_mismatch", "still wrong", nextOffset),
            )
        }

        val outcome = run(listOf(a))

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals(FakeQueue.State.TRANSIENT, queue.state("clip.mp4"))
        assertEquals("unstable offset", queue.error("clip.mp4"))
    }

    @Test
    fun `a large file goes in chunks`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "large.bin", "x".repeat(100))

        run(listOf(a), config = SyncConfig(chunkBytes = 30))

        assertEquals(4, transport.putCalls.size)
        assertEquals(listOf(0L, 30L, 60L, 90L), transport.putCalls.map { it.second })
        assertEquals(listOf(30L, 30L, 30L, 10L), transport.putCalls.map { it.third })
        assertEquals("x".repeat(100), transport.content("large.bin"))
    }

    // --- the integrity digest (§9) ---

    @Test
    fun `a small file gets its digest on the last chunk`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "small.txt", "abc")

        run(listOf(a))

        assertNotNull(transport.digestsSeen["small.txt"], "the digest should arrive on commit")
        assertEquals(64, transport.digestsSeen["small.txt"]!!.length)
    }

    @Test
    fun `a resumed file gets a digest too`(@TempDir dir: Path) = runTest {
        // We used to skip the digest on resume so as not to read the file from the start.
        // That saved the wrong thing: resuming rescues transmission, not reads from the
        // phone's storage - and it left resumed files out of the ledger, which is to say
        // precisely the largest ones, where the confirmation matters most.
        setUp()
        val a = file(dir, "clip.mp4", "0123456789")
        transport.received["clip.mp4"] = StringBuilder("01234")
        transport.manifestResponder = { ApiResult.Ok(ManifestResponse(0, listOf(NeededFile("clip.mp4", 5)))) }

        run(listOf(a))

        assertEquals(64, transport.digestsSeen["clip.mp4"]!!.length)
    }

    @Test
    fun `the digest goes with every chunk`(@TempDir dir: Path) = runTest {
        // The server only checks the digest on commit, but requires one on every request
        // (ADR-016). Sending it only on the last chunk would mean every earlier one
        // getting refused.
        setUp()
        val a = file(dir, "large.bin", "x".repeat(100))

        run(listOf(a), config = SyncConfig(chunkBytes = 30))

        val chunks = transport.putCalls.count { it.first == "large.bin" }
        assertTrue(chunks > 1, "the file was to go in several chunks, it went in $chunks")
        assertEquals(chunks, transport.digestsPerCall.count { it != null })
    }

    // --- thorough mode (ADR-016) ---

    @Test
    fun `fast mode computes no digests for the manifest`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "a.txt", "content")

        run(listOf(a), config = SyncConfig(integrityPolicy = IntegrityPolicy.FAST))

        assertNull(transport.manifestDigests["a.txt"])
    }

    @Test
    fun `thorough mode adds a digest to the manifest`(@TempDir dir: Path) = runTest {
        // This is the only difference the server sees - and it is what lets it compare the
        // client's claim against the ledger before anything is transferred.
        setUp()
        val a = file(dir, "a.txt", "content")

        run(listOf(a), config = SyncConfig(integrityPolicy = IntegrityPolicy.THOROUGH))

        assertEquals(64, transport.manifestDigests["a.txt"]?.length)
    }

    @Test
    fun `a divergence reported by the server reaches the journal`(@TempDir dir: Path) = runTest {
        // The file returns through "needed" anyway, so without a journal entry a
        // metadata-preserving substitution would be indistinguishable from an ordinary edit.
        setUp()
        val a = file(dir, "contract.pdf", "text")
        transport.manifestResponder = {
            ApiResult.Ok(
                ManifestResponse(
                    have = 0,
                    needed = listOf(NeededFile("contract.pdf", 0)),
                    divergent = listOf("contract.pdf"),
                ),
            )
        }

        run(listOf(a), config = SyncConfig(integrityPolicy = IntegrityPolicy.THOROUGH))

        assertTrue(journal.has("integrity_divergent"))
    }

    // --- errors ---

    @Test
    fun `a path rejected in the manifest is never sent at all`(@TempDir dir: Path) = runTest {
        setUp()
        val bad = file(dir, "bad.txt", "x")
        val good = file(dir, "good.txt", "y")
        transport.manifestResponder = FakeTransport.manifestRejects("bad.txt")

        run(listOf(bad, good))

        assertEquals(FakeQueue.State.PERMANENT, queue.state("bad.txt"))
        assertEquals(0, transport.putCalls.count { it.first == "bad.txt" })
        assertTrue(journal.has("path_rejected"))
    }

    @Test
    fun `a permanent error does not return to the queue`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "a.txt", "x")
        transport.putInterceptor = { _, _, _ -> FakeTransport.rejected() }

        run(listOf(a))

        assertEquals(FakeQueue.State.PERMANENT, queue.state("a.txt"))
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun `a transient error leaves the file in the queue`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "a.txt", "x")
        transport.putInterceptor = { _, _, _ -> FakeTransport.outOfSpace() }

        run(listOf(a))

        assertEquals(FakeQueue.State.TRANSIENT, queue.state("a.txt"))
        assertTrue(journal.has("insufficient_storage"))
    }

    @Test
    fun `a transient error gets one attempt per cycle`(@TempDir dir: Path) = runTest {
        // The queue hands a file that failed for now straight back. Trying it again in the
        // same cycle only repeats the failure: with the server's disk full, five quick
        // attempts would set every file aside before anyone could free the space (B29).
        setUp()
        val a = file(dir, "a.txt", "x")
        transport.putInterceptor = { _, _, _ -> FakeTransport.outOfSpace() }

        run(listOf(a))

        assertEquals(1, transport.putCalls.count { it.first == "a.txt" })
        assertEquals(FakeQueue.State.TRANSIENT, queue.state("a.txt"))
    }

    @Test
    fun `the outcome counts the files queued and the files that failed`(@TempDir dir: Path) = runTest {
        // The session row is built from these counts (B28).
        setUp()
        val ok = file(dir, "ok.txt", "x")
        val refused = file(dir, "refused.txt", "y")
        val busy = file(dir, "busy.txt", "z")
        transport.putInterceptor = { _, path, _ ->
            when (path) {
                "refused.txt" -> FakeTransport.rejected()
                "busy.txt" -> FakeTransport.outOfSpace()
                else -> null
            }
        }

        val completed = assertInstanceOf(SyncOutcome.Completed::class.java, run(listOf(ok, refused, busy)))

        assertEquals(3, completed.filesQueued)
        assertEquals(1, completed.filesSent)
        assertEquals(2, completed.filesFailed)
    }

    @Test
    fun `the attempt limit counts cycles, not retries within one`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "a.txt", "x")
        transport.putInterceptor = { _, _, _ -> FakeTransport.outOfSpace() }
        val config = SyncConfig(maxAttempts = 3)

        repeat(3) { run(listOf(a), config = config) }
        assertEquals(FakeQueue.State.TRANSIENT, queue.state("a.txt"), "three cycles, three attempts")

        run(listOf(a), config = config)

        assertEquals(FakeQueue.State.PERMANENT, queue.state("a.txt"))
        assertEquals(3, transport.putCalls.count { it.first == "a.txt" })
        assertTrue(journal.has("too_many_attempts"))
    }

    @Test
    fun `a dropped connection ends the cycle, not the file`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "a.txt", "x")
        val b = file(dir, "b.txt", "y")
        transport.putInterceptor = { _, path, _ ->
            if (path == "a.txt") FakeTransport.unreachable() else null
        }

        val outcome = run(listOf(a, b))

        val interrupted = assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(InterruptReason.CONNECTION, interrupted.reason)
        assertEquals(FakeQueue.State.TRANSIENT, queue.state("a.txt"))
    }

    @Test
    fun `an unreachable server at manifest time ends the cycle in failure`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "a.txt", "x")
        transport.manifestResponder = { ApiResult.Unreachable(java.io.IOException("no route")) }

        val outcome = run(listOf(a))

        assertInstanceOf(SyncOutcome.Failed::class.java, outcome)
        assertTrue(journal.has("manifest_unreachable"))
    }

    @Test
    fun `a file deleted after the scan is deferred without an error`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "vanishes.txt", "x")
        File(a.absolutePath).delete()

        val outcome = run(listOf(a))

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertEquals(FakeQueue.State.PERMANENT, queue.state("vanishes.txt"))
        assertEquals("the file no longer exists", queue.error("vanishes.txt"))
    }

    @Test
    fun `a file changed after the scan is not spliced from two versions`(@TempDir dir: Path) = runTest {
        // Sending it now would leave a file on the server that never existed.
        setUp()
        val a = file(dir, "growing.txt", "short")
        File(a.absolutePath).writeText("much longer content")
        File(a.absolutePath).setLastModified(mtime * 1000)

        run(listOf(a))

        assertEquals(FakeQueue.State.TRANSIENT, queue.state("growing.txt"))
        assertTrue(journal.has("file_changed"))
        assertEquals("", transport.content("growing.txt"))
    }

    @Test
    fun `a file stops returning after too many failed attempts`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "stubborn.txt", "x")
        queue.enqueue(listOf(a))
        queue.rows["stubborn.txt"]!!.attempts = 5

        run(listOf(a), config = SyncConfig(maxAttempts = 5))

        assertEquals(FakeQueue.State.PERMANENT, queue.state("stubborn.txt"))
        assertTrue(journal.has("too_many_attempts"))
    }

    // --- the budget ---

    @Test
    fun `an exhausted budget ends the cycle cleanly before uploading`(@TempDir dir: Path) = runTest {
        setUp()
        val a = file(dir, "a.txt", "x")

        val outcome = run(listOf(a), deadline = immediately)

        val interrupted = assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(InterruptReason.BUDGET, interrupted.reason)
    }

    @Test
    fun `a budget exhausted mid-queue preserves progress`(@TempDir dir: Path) = runTest {
        setUp()
        val files = (1..5).map { file(dir, "file$it.txt", "content$it") }

        var sent = 0
        val deadline = Deadline { sent >= 2 }
        transport.putInterceptor = { _, _, _ -> sent++; null }

        val outcome = run(files, deadline = deadline)

        val interrupted = assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(2, interrupted.filesSent)
        // The rest waits in the queue; the next cycle picks it up without rescanning.
        assertEquals(3, queue.pendingCount())
        assertTrue(journal.has("deadline_upload"))
    }

    @Test
    fun `a budget that runs out mid-file is a budget stop`(@TempDir dir: Path) = runTest {
        // Reported as a dropped connection, it had WorkManager retry a cycle with nothing left
        // to spend, and the night left no journal entry saying why it stopped (B31).
        setUp()
        val big = file(dir, "video.mp4", "0123456789")
        var chunks = 0
        transport.putInterceptor = { _, _, _ -> chunks++; null }

        val outcome = run(listOf(big), deadline = Deadline { chunks >= 1 }, config = SyncConfig(chunkBytes = 4))

        val interrupted = assertInstanceOf(SyncOutcome.Interrupted::class.java, outcome)
        assertEquals(InterruptReason.BUDGET, interrupted.reason)
        assertTrue(journal.has("deadline_upload"))
    }

    @Test
    fun `a file cut off mid-transfer comes back in the next cycle without a rescan`(@TempDir dir: Path) = runTest {
        // The next night's scan is incremental and lists only what changed. A file stopped
        // halfway has not changed, so only the queue can bring it back (B30).
        setUp()
        val big = file(dir, "video.mp4", "0123456789")
        var chunks = 0
        transport.putInterceptor = { _, _, _ -> chunks++; null }
        val config = SyncConfig(chunkBytes = 4)

        val first = run(listOf(big), deadline = Deadline { chunks >= 1 }, config = config)
        assertInstanceOf(SyncOutcome.Interrupted::class.java, first)

        val second = run(emptyList(), config = config)

        assertEquals(1, assertInstanceOf(SyncOutcome.Completed::class.java, second).filesSent)
        assertEquals("0123456789", transport.content("video.mp4"))
    }

    @Test
    fun `an empty scan ends in success, not in an error`() = runTest {
        setUp()

        val outcome = run(emptyList())

        assertInstanceOf(SyncOutcome.Completed::class.java, outcome)
        assertTrue(journal.has("manifest_empty"))
        assertEquals(0, transport.requests.size)
    }
}
