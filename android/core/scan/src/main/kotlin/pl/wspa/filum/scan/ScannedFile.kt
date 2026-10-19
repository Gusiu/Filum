package pl.wspa.filum.scan

import java.text.Normalizer

/**
 * A file noticed by the scanner, in the form the manifest needs.
 *
 * [canonicalPath] is **relative to the storage root**, separated by slashes and normalised
 * to Unicode NFC (§3 of PATH-RULES). NTFS sanitisation is the server's business and never
 * appears on this side.
 */
data class ScannedFile(
    val canonicalPath: String,
    val absolutePath: String,
    val size: Long,
    /** Always in **seconds** since the epoch, UTC. See [Mtime]. */
    val mtimeSeconds: Long,
)

/**
 * The one place where we convert a modification time into the protocol's form (ADR-011).
 *
 * `MediaStore.DATE_MODIFIED` gives **seconds** and `File.lastModified()` **milliseconds**.
 * If the same file reached the manifest from one source at one time and the other at
 * another, its identity would change between cycles and it would be uploaded over and over.
 * On a library of any size that is a failure rather than an inconvenience - all that
 * changes is how quickly the user notices.
 */
object Mtime {

    /** From `File.lastModified()` and other millisecond sources. */
    fun fromMillis(millis: Long): Long = Math.floorDiv(millis, 1000L)

    /** From `MediaStore.DATE_MODIFIED`; unchanged, but through the same gate. */
    fun fromSeconds(seconds: Long): Long = seconds

    fun toMillis(seconds: Long): Long = seconds * 1000L
}

object CanonicalPath {

    /**
     * A path relative to the storage root, slash-separated and in NFC form.
     *
     * NFC normalisation matters for accented names: the same name written once in NFD and
     * once in NFC is two different paths to a byte comparison, so the file would return to
     * the queue on every scan.
     */
    fun of(rootAbsolutePath: String, fileAbsolutePath: String): String? {
        val root = rootAbsolutePath.trimEnd('/', '\\')
        val file = fileAbsolutePath

        if (!file.startsWith(root)) return null

        val relative = file.substring(root.length).trimStart('/', '\\').replace('\\', '/')
        if (relative.isEmpty()) return null

        return Normalizer.normalize(relative, Normalizer.Form.NFC)
    }
}
