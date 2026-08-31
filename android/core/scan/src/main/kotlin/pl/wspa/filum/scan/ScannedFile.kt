package pl.wspa.filum.scan

import java.text.Normalizer

/**
 * Plik zauważony przez skaner, w postaci gotowej do manifestu.
 *
 * [canonicalPath] jest **względny wobec katalogu głównego pamięci**, rozdzielany
 * ukośnikiem i znormalizowany do Unicode NFC (§3 dokumentu PATH-RULES). Sanityzacja
 * pod NTFS to sprawa serwera i nigdy nie pojawia się po tej stronie.
 */
data class ScannedFile(
    val canonicalPath: String,
    val absolutePath: String,
    val size: Long,
    /** Zawsze w **sekundach** od epoki, UTC. Patrz [Mtime]. */
    val mtimeSeconds: Long,
)

/**
 * Jedyne miejsce, w którym zamieniamy czas modyfikacji na postać protokołu (ADR-011).
 *
 * `MediaStore.DATE_MODIFIED` podaje **sekundy**, a `File.lastModified()` **milisekundy**.
 * Gdyby ten sam plik trafiał do manifestu raz z jednego, raz z drugiego źródła, jego
 tożsamość zmieniałaby się między cyklami i byłby wysyłany w kółko. Na bibliotece
 * dowolnej wielkości to nie jest niedogodność, tylko awaria — zmienia się tylko to,
 * jak szybko użytkownik ją zauważy.
 */
object Mtime {

    /** Z `File.lastModified()` i innych źródeł milisekundowych. */
    fun fromMillis(millis: Long): Long = Math.floorDiv(millis, 1000L)

    /** Z `MediaStore.DATE_MODIFIED`; przechodzi bez zmian, ale przez tę samą bramę. */
    fun fromSeconds(seconds: Long): Long = seconds

    fun toMillis(seconds: Long): Long = seconds * 1000L
}

object CanonicalPath {

    /**
     * Ścieżka względna wobec katalogu głównego pamięci, z ukośnikami i w postaci NFC.
     *
     * Normalizacja NFC jest istotna dla polskich znaków: ta sama nazwa zapisana raz
     * w NFD, raz w NFC to dla porównania bajtowego dwie różne ścieżki, więc plik
     * wracałby do kolejki przy każdym skanie.
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
