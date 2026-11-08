package pl.wspa.filum.sync

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.wspa.filum.net.ApiResult
import pl.wspa.filum.protocol.ManifestRequest
import pl.wspa.filum.scan.ScannedFile
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * The warning about many files already in the backup changing in one cycle - what encryption
 * by ransomware looks like from the phone. The backup keeps every previous version either way;
 * the warning is there so the person learns of it now.
 */
class MassChangeTest {

    private val firstMtime = 1_756_400_000L
    private val laterMtime = firstMtime + 3600
    private val noDeadline = Deadline { false }

    private val queue = FakeQueue()
    private val journal = FakeJournal()
    private val transport = FakeTransport()

    private fun write(dir: Path, name: String, content: String, mtime: Long): ScannedFile {
        val file = File(dir.toFile(), name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.setLastModified(mtime * 1000)
        return ScannedFile(name, file.absolutePath, content.length.toLong(), mtime)
    }

    /** The photos with these [numbers]; a different [version] gives every one of them another size. */
    private fun photos(dir: Path, numbers: IntRange, version: String, mtime: Long) =
        numbers.map { write(dir, "DCIM/photo$it.jpg", "$version-$it", mtime) }

    private suspend fun cycle(files: List<ScannedFile>, config: SyncConfig = SyncConfig()): SyncOutcome =
        SyncEngine(transport, queue, journal, config)
            .run("session", ManifestRequest.MODE_DELTA, files.asSequence(), noDeadline)

    private fun warnings() = journal.entries.filter { it.code == "mass_change" }

    @Test
    fun `many files already in the backup changing in one cycle raise one warning`(@TempDir dir: Path) = runTest {
        cycle(photos(dir, 1..60, "original", firstMtime))

        val outcome = cycle(photos(dir, 1..60, "encrypted-content", laterMtime))

        val warning = warnings().single()
        assertEquals(JournalLevel.WARN, warning.level)
        assertTrue("60 files" in warning.message, warning.message)
        // A warning, not a stop: the changed files go to the backup as new versions.
        assertEquals(60, assertInstanceOf(SyncOutcome.Completed::class.java, outcome).filesSent)
    }

    @Test
    fun `new files alone raise no warning`(@TempDir dir: Path) = runTest {
        // A first backup, or a day of photos, brings many files the backup has never had.
        cycle(photos(dir, 1..200, "new", firstMtime))

        assertEquals(emptyList<FakeJournal.Entry>(), warnings())
    }

    @Test
    fun `a few changed files raise no warning`(@TempDir dir: Path) = runTest {
        val original = photos(dir, 1..60, "original", firstMtime)
        cycle(original)

        val edited = original.take(50) + photos(dir, 51..60, "edited", laterMtime)
        cycle(edited)

        assertEquals(emptyList<FakeJournal.Entry>(), warnings())
    }

    @Test
    fun `files that never reached the backup do not count`(@TempDir dir: Path) = runTest {
        transport.manifestResponder = { ApiResult.Unreachable(IOException("no route")) }
        cycle(photos(dir, 1..60, "original", firstMtime))

        transport.manifestResponder = FakeTransport().manifestResponder
        cycle(photos(dir, 1..60, "changed-before-it-was-sent", laterMtime))

        assertEquals(emptyList<FakeJournal.Entry>(), warnings())
    }

    @Test
    fun `the number of changed files that raises the warning comes from the configuration`(@TempDir dir: Path) = runTest {
        val config = SyncConfig(massChangeFiles = 3)
        cycle(photos(dir, 1..3, "original", firstMtime), config)

        cycle(photos(dir, 1..3, "changed", laterMtime), config)

        assertTrue("3 files" in warnings().single().message)
    }

    @Test
    fun `a cycle of several manifest batches warns once, with every changed file counted`(@TempDir dir: Path) = runTest {
        val config = SyncConfig(manifestBatch = 10)
        cycle(photos(dir, 1..60, "original", firstMtime), config)

        cycle(photos(dir, 1..60, "encrypted-content", laterMtime), config)

        assertTrue("60 files" in warnings().single().message)
    }

    @Test
    fun `a cycle that cannot reach the server still warns of what changed on the phone`(@TempDir dir: Path) = runTest {
        cycle(photos(dir, 1..60, "original", firstMtime))

        transport.manifestResponder = { ApiResult.Unreachable(IOException("no route")) }
        val outcome = cycle(photos(dir, 1..60, "encrypted-content", laterMtime))

        assertInstanceOf(SyncOutcome.Failed::class.java, outcome)
        assertTrue("60 files" in warnings().single().message)
    }
}
