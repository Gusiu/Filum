package pl.wspa.filum.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.text.Normalizer

/**
 * Normalising the modification time (ADR-011).
 *
 * This looks like a detail and is one of the two things that can turn a working
 * application into one that uploads the whole library over and over.
 */
class MtimeTest {

    @Test
    fun `milliseconds are truncated to whole seconds`() {
        assertEquals(1_756_400_000, Mtime.fromMillis(1_756_400_000_999))
        assertEquals(1_756_400_000, Mtime.fromMillis(1_756_400_000_000))
    }

    @Test
    fun `seconds pass through unchanged`() {
        assertEquals(1_756_400_000, Mtime.fromSeconds(1_756_400_000))
    }

    @Test
    fun `both sources give the same result for the same instant`() {
        // This is the whole point of the rule: a file read once from MediaStore and once
        // from the filesystem has to have an identical identity, or it returns every scan.
        val epochSeconds = 1_756_400_000L

        assertEquals(Mtime.fromSeconds(epochSeconds), Mtime.fromMillis(epochSeconds * 1000 + 750))
    }

    @Test
    fun `truncation works before the epoch too`() {
        // floorDiv, not integer division: -1 ms is still second -1, not 0.
        assertEquals(-1, Mtime.fromMillis(-1))
        assertEquals(-1, Mtime.fromMillis(-1000))
        assertEquals(-2, Mtime.fromMillis(-1001))
    }
}

class CanonicalPathTest {

    @Test
    fun `the path is relative to the root directory`() {
        assertEquals(
            "DCIM/Camera/IMG_0001.JPG",
            CanonicalPath.of("/storage/emulated/0", "/storage/emulated/0/DCIM/Camera/IMG_0001.JPG"),
        )
    }

    @Test
    fun `a trailing slash on the root directory does no harm`() {
        assertEquals(
            "DCIM/a.jpg",
            CanonicalPath.of("/storage/emulated/0/", "/storage/emulated/0/DCIM/a.jpg"),
        )
    }

    @Test
    fun `a file outside the root directory is rejected`() {
        assertNull(CanonicalPath.of("/storage/emulated/0", "/data/local/tmp/file"))
    }

    @Test
    fun `the root directory itself is not a file`() {
        assertNull(CanonicalPath.of("/storage/emulated/0", "/storage/emulated/0"))
    }

    @Test
    fun `the name is normalised to NFC`() {
        // The same name in NFD and in NFC is two different paths to a byte comparison,
        // so a file with accented characters would return to the queue on every scan.
        val nfd = Normalizer.normalize("zdjęcie-łąka.jpg", Normalizer.Form.NFD)
        val nfc = Normalizer.normalize("zdjęcie-łąka.jpg", Normalizer.Form.NFC)
        assertTrue(nfd != nfc, "the test only means anything if the two forms differ")

        assertEquals(
            CanonicalPath.of("/root", "/root/$nfc"),
            CanonicalPath.of("/root", "/root/$nfd"),
        )
    }
}

class ExclusionRulesTest {

    private val rules = ExclusionRules()

    @Test
    fun `directories inaccessible since Android 11 are skipped`() {
        assertTrue(rules.isExcluded("Android/data/com.example/file.dat"))
        assertTrue(rules.isExcluded("Android/obb/com.game/data.obb"))
    }

    @Test
    fun `Android media is NOT skipped`() {
        // WhatsApp lives there among others - exactly the data a user does not want to
        // lose. The directory is accessible, so we cover it.
        assertFalse(rules.isExcluded("Android/media/com.whatsapp/WhatsApp/Media/photo.jpg"))
    }

    @Test
    fun `thumbnails and caches are skipped at any depth`() {
        assertTrue(rules.isExcluded("DCIM/.thumbnails/1234.jpg"))
        assertTrue(rules.isExcluded("Pictures/album/.thumbnails/x.jpg"))
        assertTrue(rules.isExcluded("Documents/cache/temp.bin"))
    }

    @Test
    fun `trashed and pending files are skipped`() {
        assertTrue(rules.isExcluded("DCIM/Camera/.trashed-1699999999-IMG_0001.JPG"))
        assertTrue(rules.isExcluded("DCIM/Camera/.pending-1699999999-VID_0001.mp4"))
    }

    @Test
    fun `ordinary files pass through`() {
        assertFalse(rules.isExcluded("DCIM/Camera/IMG_0001.JPG"))
        assertFalse(rules.isExcluded("Documents/thesis/chapter.docx"))
        assertFalse(rules.isExcluded("Download/installer.apk"))
    }

    @Test
    fun `a directory name resembling an excluded one is not excluded`() {
        assertFalse(rules.isExcluded("Pictures/cachet/photo.jpg"))
        assertFalse(rules.isExcluded("Android/dataset/file.txt"))
    }

    @Test
    fun `the user's exclusions add to the defaults`() {
        val custom = ExclusionRules(userPrefixes = listOf("Download", "/Movies/scratch/"))

        assertTrue(custom.isExcluded("Download/large.iso"))
        assertTrue(custom.isExcluded("Movies/scratch/clip.mp4"))
        assertTrue(custom.isExcluded("Android/data/x/y"), "the defaults still apply")
        assertFalse(custom.isExcluded("Movies/holiday.mp4"))
    }

    @Test
    fun `the default exclusions can be turned off`() {
        val bare = ExclusionRules(userPrefixes = emptyList(), includeDefaults = false)

        assertFalse(bare.isExcluded("Android/data/x/y"))
    }

    @Test
    fun `an excluded directory prunes the whole subtree`() {
        assertTrue(rules.isExcludedDirectory("Android/data"))
        assertTrue(rules.isExcludedDirectory("DCIM/.thumbnails"))
        assertFalse(rules.isExcludedDirectory("DCIM/Camera"))
        assertFalse(rules.isExcludedDirectory(""))
    }
}

/**
 * Walking the directory tree. The class is plain Kotlin on the JVM, so we test it
 * against real files in a temporary directory - with no emulator and no fakes.
 */
class FilesystemScannerTest {

    private val now = 1_756_400_000L

    private fun File.withMtime(seconds: Long) = apply { setLastModified(seconds * 1000) }

    private fun file(root: File, path: String, content: String, mtime: Long = now - 3600): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file.withMtime(mtime)
    }

    private fun scanner(root: File, exclusions: ExclusionRules = ExclusionRules()) =
        FilesystemScanner(root, exclusions, nowSeconds = { now })

    @Test
    fun `it finds files across the whole tree`(@TempDir dir: Path) {
        val root = dir.toFile()
        file(root, "DCIM/Camera/IMG_0001.JPG", "aaa")
        file(root, "Documents/thesis/chapter.txt", "bbbb")
        file(root, "music.mp3", "cc")

        val found = scanner(root).walk().associateBy { it.canonicalPath }

        assertEquals(3, found.size)
        assertEquals(3, found["DCIM/Camera/IMG_0001.JPG"]!!.size)
        assertEquals(4, found["Documents/thesis/chapter.txt"]!!.size)
    }

    @Test
    fun `the modification time is in seconds`(@TempDir dir: Path) {
        val root = dir.toFile()
        file(root, "a.txt", "x", mtime = 1_700_000_000)

        assertEquals(1_700_000_000, scanner(root).walk().single().mtimeSeconds)
    }

    @Test
    fun `an excluded subtree is never visited`(@TempDir dir: Path) {
        val root = dir.toFile()
        file(root, "Android/data/com.example/large.dat", "x")
        file(root, "Android/media/com.whatsapp/photo.jpg", "y")
        file(root, "DCIM/a.jpg", "z")

        val paths = scanner(root).walk().map { it.canonicalPath }.toSet()

        assertEquals(setOf("Android/media/com.whatsapp/photo.jpg", "DCIM/a.jpg"), paths)
    }

    @Test
    fun `a file being written right now is skipped`(@TempDir dir: Path) {
        // Sending half a recording would leave a file on the server that looks
        // complete but is not.
        val root = dir.toFile()
        file(root, "Movies/recording.mp4", "hi", mtime = now - 5)
        file(root, "Movies/finished.mp4", "whole", mtime = now - 600)

        val skipped = mutableListOf<String>()
        val found = FilesystemScanner(
            root, ExclusionRules(), nowSeconds = { now },
            onSkipped = { path, _ -> skipped += path },
        ).walk().map { it.canonicalPath }.toList()

        assertEquals(listOf("Movies/finished.mp4"), found)
        assertEquals(listOf("Movies/recording.mp4"), skipped)
    }

    @Test
    fun `empty files are skipped`(@TempDir dir: Path) {
        val root = dir.toFile()
        file(root, "pusty.txt", "")
        file(root, "complete.txt", "x")

        assertEquals(listOf("complete.txt"), scanner(root).walk().map { it.canonicalPath }.toList())
    }

    @Test
    fun `a deep tree does not overflow the stack`(@TempDir dir: Path) {
        val root = dir.toFile()
        val deep = (1..200).joinToString("/") { "level$it" }
        file(root, "$deep/at-the-bottom.txt", "x")

        assertEquals(1, scanner(root).walk().count())
    }

    @Test
    fun `the walk is lazy`(@TempDir dir: Path) {
        // Sequence, not List: across a hundred thousand files, collecting everything at
        // once is a needless risk, and we process in batches of 2000 anyway.
        val root = dir.toFile()
        repeat(50) { file(root, "folder$it/file.txt", "x") }

        assertEquals(5, scanner(root).walk().take(5).count())
    }
}
