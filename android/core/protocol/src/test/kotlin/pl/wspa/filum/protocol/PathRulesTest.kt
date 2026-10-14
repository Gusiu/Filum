package pl.wspa.filum.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for the rules in docs/PATH-RULES.md. The vector set is shared with the C#
 * implementation - a divergence between the two shows up here, not on a device.
 */
class PathRulesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("wektory")
    fun `sanitize matches the vector`(vector: PathVector) {
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
    fun `decode reverses sanitize for every vector`() {
        for (vector in TestVectors.load().filterNot { it.isRejection }) {
            val stored = (PathRules.sanitize(vector.input) as SanitizeResult.Success).stored
            assertEquals(vector.input, PathRules.decode(stored), "not reversed: ${vector.input}")
        }
    }

    @Test
    fun `the vector file is complete`() {
        // A guard against the file being silently trimmed, e.g. by an editor stripping
        // trailing whitespace, or by a badly resolved merge.
        assertTrue(TestVectors.load().size >= 30)
    }

    // --- injectivity: the reason we percent-encode at all (ADR-009) ---

    @Test
    fun `the encoding is injective for forbidden characters`() {
        // Replacing forbidden characters with '_' would collapse both paths into one and
        // the second file would silently overwrite the first. This tests exactly that.
        assertNotEquals(stored("Documents/a<b.txt"), stored("Documents/a_b.txt"))
    }

    @Test
    fun `the encoding is injective for text that looks encoded`() {
        // If '%' were not encoded first, both names would collide after decoding.
        val a = stored("Documents/a%3Ab.txt")
        val b = stored("Documents/a:b.txt")

        assertNotEquals(a, b)
        assertEquals("Documents/a%3Ab.txt", PathRules.decode(a))
        assertEquals("Documents/a:b.txt", PathRules.decode(b))
    }

    @Test
    fun `the encoding is injective across the whole vector set`() {
        val results = TestVectors.load().filterNot { it.isRejection }.map { stored(it.input) }

        assertEquals(results.size, results.toSet().size)
    }

    // --- case collisions (§6) ---

    @Test
    fun `the collision suffix is stable`() {
        // The value is stated outright in docs/PATH-RULES.md §6 - the document, C# and
        // Kotlin have to agree, or the server and the client diverge on disk.
        assertEquals("~6a8a8e", PathRules.caseCollisionSuffix("Pictures/photo.jpg"))
    }

    @Test
    fun `the collision suffix differs by letter case`() {
        assertNotEquals(
            PathRules.caseCollisionSuffix("Pictures/foto.jpg"),
            PathRules.caseCollisionSuffix("Pictures/Foto.jpg"),
        )
    }

    // --- component length limit (§5 rule 6) ---

    @Test
    fun `a long component is truncated with a suffix`() {
        val encoded = PathRules.encodeSegment("a".repeat(300))

        assertTrue(encoded.length <= 255, "length ${encoded.length} exceeds the NTFS limit")
        assertTrue(encoded.contains('~'))
    }

    @Test
    fun `a long component never splits an escape sequence`() {
        // Each character encodes to three, so the truncation boundary falls inside a %XX
        // sequence. Splitting it would destroy reversibility.
        val encoded = PathRules.encodeSegment(":".repeat(200))
        val body = encoded.substring(0, encoded.lastIndexOf('~'))

        assertEquals(0, body.length % 3)
        assertTrue(!body.takeLast(2).contains("%3"))
    }

    @Test
    fun `a long component differs for different sources`() {
        assertNotEquals(
            PathRules.encodeSegment("a".repeat(300) + "x"),
            PathRules.encodeSegment("a".repeat(300) + "y"),
        )
    }

    // --- individual rules, in case the vectors ever narrow their coverage ---

    @ParameterizedTest
    @ValueSource(strings = ["CON", "con", "CoN.txt", "LPT1.dat", "nul"])
    fun `reserved names have their first character encoded`(segment: String) {
        assertTrue(PathRules.encodeSegment(segment).startsWith("%"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["CONSOLE.txt", "COM10.log", "AUXILIARY", "comment.txt"])
    fun `names resembling reserved ones are left alone`(segment: String) {
        assertEquals(segment, PathRules.encodeSegment(segment))
    }

    @Test
    fun `trailing dots and spaces encode only the last character`() {
        assertEquals("file%2E", PathRules.encodeSegment("file."))
        assertEquals("file%20", PathRules.encodeSegment("file "))
        assertEquals("file.%2E", PathRules.encodeSegment("file.."))
        assertEquals("file %20", PathRules.encodeSegment("file  "))
        assertEquals("a.b.c", PathRules.encodeSegment("a.b.c"))
    }

    @Test
    fun `validation measures length in utf8 bytes, not characters`() {
        // 200 characters with diacritics come to 400 bytes in UTF-8.
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
