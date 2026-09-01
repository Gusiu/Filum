package pl.wspa.filum.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Testy reguł z docs/PATH-RULES.md. Zestaw wektorów jest wspólny z implementacją
 * w C# — rozjazd między stronami ujawni się tutaj, a nie na urządzeniu.
 */
class PathRulesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("wektory")
    fun `sanitize zgadza sie z wektorem`(vector: PathVector) {
        when (val result = PathRules.sanitize(vector.input)) {
            is SanitizeResult.Rejected -> {
                assertTrue(vector.isRejection, "nieoczekiwane odrzucenie: ${result.reason}")
                assertEquals(vector.expectedRejection, result.reason)
            }
            is SanitizeResult.Success -> {
                assertTrue(!vector.isRejection, "oczekiwano odrzucenia, otrzymano ${result.stored}")
                assertEquals(vector.expected, result.stored)
            }
        }
    }

    @Test
    fun `decode odwraca sanitize dla wszystkich wektorow`() {
        for (vector in TestVectors.load().filterNot { it.isRejection }) {
            val stored = (PathRules.sanitize(vector.input) as SanitizeResult.Success).stored
            assertEquals(vector.input, PathRules.decode(stored), "nie odwrócono: ${vector.input}")
        }
    }

    @Test
    fun `plik wektorow jest kompletny`() {
        // Zabezpieczenie przed cichym okrojeniem pliku, np. przez edytor obcinający
        // końcowe białe znaki albo błędnie scalony merge.
        assertTrue(TestVectors.load().size >= 30)
    }

    // --- różnowartościowość: powód, dla którego kodujemy procentowo (ADR-009) ---

    @Test
    fun `kodowanie jest roznowartosciowe dla znakow zabronionych`() {
        // Podmiana znaków zabronionych na '_' sprowadziłaby obie ścieżki do jednej
        // i drugi plik cicho nadpisałby pierwszy. To jest test tej właśnie regresji.
        assertNotEquals(stored("Documents/a<b.txt"), stored("Documents/a_b.txt"))
    }

    @Test
    fun `kodowanie jest roznowartosciowe dla tekstu wygladajacego na zakodowany`() {
        // Gdyby '%' nie było kodowane jako pierwsze, obie nazwy zderzyłyby się po dekodowaniu.
        val a = stored("Documents/a%3Ab.txt")
        val b = stored("Documents/a:b.txt")

        assertNotEquals(a, b)
        assertEquals("Documents/a%3Ab.txt", PathRules.decode(a))
        assertEquals("Documents/a:b.txt", PathRules.decode(b))
    }

    @Test
    fun `kodowanie jest roznowartosciowe na pelnym zestawie wektorow`() {
        val results = TestVectors.load().filterNot { it.isRejection }.map { stored(it.input) }

        assertEquals(results.size, results.toSet().size)
    }

    // --- kolizje wielkości liter (§6) ---

    @Test
    fun `przyrostek kolizji jest stabilny`() {
        // Wartość podana wprost w docs/PATH-RULES.md §6 — dokumentacja, C# i Kotlin
        // muszą dawać ten sam wynik, inaczej serwer i klient rozjadą się na dysku.
        assertEquals("~6a8a8e", PathRules.caseCollisionSuffix("Pictures/photo.jpg"))
    }

    @Test
    fun `przyrostek kolizji rozni sie dla roznej wielkosci liter`() {
        assertNotEquals(
            PathRules.caseCollisionSuffix("Pictures/foto.jpg"),
            PathRules.caseCollisionSuffix("Pictures/Foto.jpg"),
        )
    }

    // --- limit długości składowej (§5 reguła 6) ---

    @Test
    fun `dluga skladowa jest przycinana z przyrostkiem`() {
        val encoded = PathRules.encodeSegment("a".repeat(300))

        assertTrue(encoded.length <= 255, "długość ${encoded.length} przekracza limit NTFS")
        assertTrue(encoded.contains('~'))
    }

    @Test
    fun `dluga skladowa nie rozcina sekwencji ucieczki`() {
        // Każdy znak koduje się na 3 znaki, więc granica przycięcia wypada w środku
        // sekwencji %XX. Rozcięcie zabiłoby odwracalność.
        val encoded = PathRules.encodeSegment(":".repeat(200))
        val body = encoded.substring(0, encoded.lastIndexOf('~'))

        assertEquals(0, body.length % 3)
        assertTrue(!body.takeLast(2).contains("%3"))
    }

    @Test
    fun `dluga skladowa rozni sie dla roznych zrodel`() {
        assertNotEquals(
            PathRules.encodeSegment("a".repeat(300) + "x"),
            PathRules.encodeSegment("a".repeat(300) + "y"),
        )
    }

    // --- pojedyncze reguły, gdyby wektory kiedyś zawęziły zakres ---

    @ParameterizedTest
    @ValueSource(strings = ["CON", "con", "CoN.txt", "LPT1.dat", "nul"])
    fun `nazwy zastrzezone maja zakodowany pierwszy znak`(segment: String) {
        assertTrue(PathRules.encodeSegment(segment).startsWith("%"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["CONSOLE.txt", "COM10.log", "AUXILIARY", "comment.txt"])
    fun `nazwy podobne do zastrzezonych zostaja bez zmian`(segment: String) {
        assertEquals(segment, PathRules.encodeSegment(segment))
    }

    @Test
    fun `koncowe kropki i spacje koduja tylko ostatni znak`() {
        assertEquals("plik%2E", PathRules.encodeSegment("plik."))
        assertEquals("plik%20", PathRules.encodeSegment("plik "))
        assertEquals("plik.%2E", PathRules.encodeSegment("plik.."))
        assertEquals("plik %20", PathRules.encodeSegment("plik  "))
        assertEquals("a.b.c", PathRules.encodeSegment("a.b.c"))
    }

    @Test
    fun `walidacja liczy dlugosc w bajtach utf8 nie w znakach`() {
        // 200 znaków z polskimi diakrytykami to 400 bajtów w UTF-8.
        val path = "ą".repeat(200)

        assertEquals(400, path.toByteArray(Charsets.UTF_8).size)
        assertEquals(PathRejection.NONE, PathRules.validate(path, maxPathBytes = 400))
        assertEquals(PathRejection.PATH_TOO_LONG, PathRules.validate(path, maxPathBytes = 399))
    }

    private fun stored(canonical: String): String =
        (PathRules.sanitize(canonical) as SanitizeResult.Success).stored

    companion object {
        @JvmStatic
        fun wektory(): List<PathVector> = TestVectors.load()
    }
}
