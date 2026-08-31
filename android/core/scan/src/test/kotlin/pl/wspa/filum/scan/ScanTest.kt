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
 * Normalizacja czasu modyfikacji (ADR-011).
 *
 To wygląda na drobiazg, a jest jedną z dwóch rzeczy, które potrafią zamienić
 * działającą aplikację w taką, która wysyła całą bibliotekę w kółko.
 */
class MtimeTest {

    @Test
    fun `milisekundy sa scinane do pelnych sekund`() {
        assertEquals(1_756_400_000, Mtime.fromMillis(1_756_400_000_999))
        assertEquals(1_756_400_000, Mtime.fromMillis(1_756_400_000_000))
    }

    @Test
    fun `sekundy przechodza bez zmian`() {
        assertEquals(1_756_400_000, Mtime.fromSeconds(1_756_400_000))
    }

    @Test
    fun `oba zrodla daja ten sam wynik dla tej samej chwili`() {
        // O to chodzi w całej regule: plik odczytany raz z MediaStore, raz z systemu
        // plików musi mieć identyczną tożsamość, inaczej wraca do kolejki co skan.
        val sekundy = 1_756_400_000L

        assertEquals(Mtime.fromSeconds(sekundy), Mtime.fromMillis(sekundy * 1000 + 750))
    }

    @Test
    fun `scinanie dziala takze przed epoka`() {
        // floorDiv, nie dzielenie całkowite: -1 ms to nadal sekunda -1, a nie 0.
        assertEquals(-1, Mtime.fromMillis(-1))
        assertEquals(-1, Mtime.fromMillis(-1000))
        assertEquals(-2, Mtime.fromMillis(-1001))
    }
}

class CanonicalPathTest {

    @Test
    fun `sciezka jest wzgledna wobec katalogu glownego`() {
        assertEquals(
            "DCIM/Camera/IMG_0001.JPG",
            CanonicalPath.of("/storage/emulated/0", "/storage/emulated/0/DCIM/Camera/IMG_0001.JPG"),
        )
    }

    @Test
    fun `koncowy ukosnik w katalogu glownym nie przeszkadza`() {
        assertEquals(
            "DCIM/a.jpg",
            CanonicalPath.of("/storage/emulated/0/", "/storage/emulated/0/DCIM/a.jpg"),
        )
    }

    @Test
    fun `plik spoza katalogu glownego jest odrzucany`() {
        assertNull(CanonicalPath.of("/storage/emulated/0", "/data/local/tmp/plik"))
    }

    @Test
    fun `sam katalog glowny nie jest plikiem`() {
        assertNull(CanonicalPath.of("/storage/emulated/0", "/storage/emulated/0"))
    }

    @Test
    fun `nazwa jest normalizowana do postaci NFC`() {
        // Ta sama nazwa w NFD i NFC to dla porównania bajtowego dwie różne ścieżki,
        // więc plik z polskimi znakami wracałby do kolejki przy każdym skanie.
        val nfd = Normalizer.normalize("zdjęcie-łąka.jpg", Normalizer.Form.NFD)
        val nfc = Normalizer.normalize("zdjęcie-łąka.jpg", Normalizer.Form.NFC)
        assertTrue(nfd != nfc, "test ma sens tylko gdy obie postacie się różnią")

        assertEquals(
            CanonicalPath.of("/root", "/root/$nfc"),
            CanonicalPath.of("/root", "/root/$nfd"),
        )
    }
}

class ExclusionRulesTest {

    private val rules = ExclusionRules()

    @Test
    fun `katalogi niedostepne od Androida 11 sa pomijane`() {
        assertTrue(rules.isExcluded("Android/data/com.example/plik.dat"))
        assertTrue(rules.isExcluded("Android/obb/com.gra/dane.obb"))
    }

    @Test
    fun `Android media NIE jest pomijany`() {
        // Tam mieszka między innymi WhatsApp — dokładnie te dane, których użytkownik
        // nie chce stracić. Katalog jest dostępny, więc go obejmujemy.
        assertFalse(rules.isExcluded("Android/media/com.whatsapp/WhatsApp/Media/foto.jpg"))
    }

    @Test
    fun `miniatury i pamieci podreczne sa pomijane na dowolnej glebokosci`() {
        assertTrue(rules.isExcluded("DCIM/.thumbnails/1234.jpg"))
        assertTrue(rules.isExcluded("Pictures/album/.thumbnails/x.jpg"))
        assertTrue(rules.isExcluded("Documents/cache/tymczasowy.bin"))
    }

    @Test
    fun `pliki w koszu i w trakcie zapisu sa pomijane`() {
        assertTrue(rules.isExcluded("DCIM/Camera/.trashed-1699999999-IMG_0001.JPG"))
        assertTrue(rules.isExcluded("DCIM/Camera/.pending-1699999999-VID_0001.mp4"))
    }

    @Test
    fun `zwykle pliki przechodza`() {
        assertFalse(rules.isExcluded("DCIM/Camera/IMG_0001.JPG"))
        assertFalse(rules.isExcluded("Documents/praca/rozdzial.docx"))
        assertFalse(rules.isExcluded("Download/instalator.apk"))
    }

    @Test
    fun `nazwa katalogu podobna do wykluczonej nie jest wykluczana`() {
        assertFalse(rules.isExcluded("Pictures/cacheteka/foto.jpg"))
        assertFalse(rules.isExcluded("Android/dataset/plik.txt"))
    }

    @Test
    fun `wykluczenia uzytkownika dochodza do domyslnych`() {
        val custom = ExclusionRules(userPrefixes = listOf("Download", "/Movies/robocze/"))

        assertTrue(custom.isExcluded("Download/duzy.iso"))
        assertTrue(custom.isExcluded("Movies/robocze/klip.mp4"))
        assertTrue(custom.isExcluded("Android/data/x/y"), "domyślne nadal obowiązują")
        assertFalse(custom.isExcluded("Movies/wakacje.mp4"))
    }

    @Test
    fun `mozna zrezygnowac z wykluczen domyslnych`() {
        val gole = ExclusionRules(userPrefixes = emptyList(), includeDefaults = false)

        assertFalse(gole.isExcluded("Android/data/x/y"))
    }

    @Test
    fun `wykluczony katalog pozwala uciac cale poddrzewo`() {
        assertTrue(rules.isExcludedDirectory("Android/data"))
        assertTrue(rules.isExcludedDirectory("DCIM/.thumbnails"))
        assertFalse(rules.isExcludedDirectory("DCIM/Camera"))
        assertFalse(rules.isExcludedDirectory(""))
    }
}

/**
 * Obchód drzewa katalogów. Klasa jest czystym Kotlinem na JVM, więc testujemy ją
 * na prawdziwych plikach w katalogu tymczasowym — bez emulatora i bez atrap.
 */
class FilesystemScannerTest {

    private val teraz = 1_756_400_000L

    private fun File.withMtime(seconds: Long) = apply { setLastModified(seconds * 1000) }

    private fun plik(root: File, path: String, content: String, mtime: Long = teraz - 3600): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file.withMtime(mtime)
    }

    private fun scanner(root: File, exclusions: ExclusionRules = ExclusionRules()) =
        FilesystemScanner(root, exclusions, nowSeconds = { teraz })

    @Test
    fun `znajduje pliki w calym drzewie`(@TempDir dir: Path) {
        val root = dir.toFile()
        plik(root, "DCIM/Camera/IMG_0001.JPG", "aaa")
        plik(root, "Documents/praca/rozdzial.txt", "bbbb")
        plik(root, "muzyka.mp3", "cc")

        val found = scanner(root).walk().associateBy { it.canonicalPath }

        assertEquals(3, found.size)
        assertEquals(3, found["DCIM/Camera/IMG_0001.JPG"]!!.size)
        assertEquals(4, found["Documents/praca/rozdzial.txt"]!!.size)
    }

    @Test
    fun `czas modyfikacji jest w sekundach`(@TempDir dir: Path) {
        val root = dir.toFile()
        plik(root, "a.txt", "x", mtime = 1_700_000_000)

        assertEquals(1_700_000_000, scanner(root).walk().single().mtimeSeconds)
    }

    @Test
    fun `wykluczone poddrzewo nie jest odwiedzane`(@TempDir dir: Path) {
        val root = dir.toFile()
        plik(root, "Android/data/com.example/duzy.dat", "x")
        plik(root, "Android/media/com.whatsapp/foto.jpg", "y")
        plik(root, "DCIM/a.jpg", "z")

        val paths = scanner(root).walk().map { it.canonicalPath }.toSet()

        assertEquals(setOf("Android/media/com.whatsapp/foto.jpg", "DCIM/a.jpg"), paths)
    }

    @Test
    fun `plik zapisywany w tej chwili jest pomijany`(@TempDir dir: Path) {
        // Wysłanie połowy nagrania dałoby na serwerze plik, który wygląda
        // na kompletny, a nie jest.
        val root = dir.toFile()
        plik(root, "Movies/nagrywany.mp4", "czesc", mtime = teraz - 5)
        plik(root, "Movies/gotowy.mp4", "calosc", mtime = teraz - 600)

        val pominięte = mutableListOf<String>()
        val found = FilesystemScanner(
            root, ExclusionRules(), nowSeconds = { teraz },
            onSkipped = { path, _ -> pominięte += path },
        ).walk().map { it.canonicalPath }.toList()

        assertEquals(listOf("Movies/gotowy.mp4"), found)
        assertEquals(listOf("Movies/nagrywany.mp4"), pominięte)
    }

    @Test
    fun `puste pliki sa pomijane`(@TempDir dir: Path) {
        val root = dir.toFile()
        plik(root, "pusty.txt", "")
        plik(root, "pelny.txt", "x")

        assertEquals(listOf("pelny.txt"), scanner(root).walk().map { it.canonicalPath }.toList())
    }

    @Test
    fun `gleboka struktura nie przepelnia stosu`(@TempDir dir: Path) {
        val root = dir.toFile()
        val deep = (1..200).joinToString("/") { "poziom$it" }
        plik(root, "$deep/na-dnie.txt", "x")

        assertEquals(1, scanner(root).walk().count())
    }

    @Test
    fun `obchod jest leniwy`(@TempDir dir: Path) {
        // Sequence, nie List: przy stu tysiącach plików zebranie wszystkiego naraz
        // jest niepotrzebnym ryzykiem, a i tak przetwarzamy partiami po 2000.
        val root = dir.toFile()
        repeat(50) { plik(root, "katalog$it/plik.txt", "x") }

        assertEquals(5, scanner(root).walk().take(5).count())
    }
}
