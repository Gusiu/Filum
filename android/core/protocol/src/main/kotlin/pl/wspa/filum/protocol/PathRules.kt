package pl.wspa.filum.protocol

import java.security.MessageDigest

/** Powód odrzucenia ścieżki. Odpowiada polu `error` w odpowiedzi HTTP 422. */
enum class PathRejection {
    NONE,
    ABSOLUTE,
    TRAVERSAL,
    DOT_SEGMENT,
    EMPTY_SEGMENT,
    NUL,
    PATH_TOO_LONG,
}

/** Wynik sanityzacji: albo ścieżka składowania, albo powód odrzucenia. */
sealed interface SanitizeResult {
    data class Success(val stored: String) : SanitizeResult

    data class Rejected(val reason: PathRejection) : SanitizeResult
}

/**
 * Odwzorowanie ścieżki kanonicznej z telefonu na ścieżkę składowania pod NTFS.
 *
 * Implementacja normatywna dla docs/PATH-RULES.md. Lustrzanym odbiciem jest klasa
 * `Filum.Protocol.PathRules` po stronie serwera — obie przechodzą ten sam zestaw
 * docs/testvectors/paths.tsv.
 *
 * Moduł jest czystym Kotlinem na JVM, bez zależności od Androida: reguły dają się
 * przetestować bez emulatora, a błąd tutaj kosztuje ciche skasowanie pliku.
 */
object PathRules {

    const val DEFAULT_MAX_PATH_BYTES: Int = 4096

    /** Limit NTFS na pojedynczą składową ścieżki, w jednostkach UTF-16. */
    private const val MAX_SEGMENT_LENGTH = 255

    /** Docelowa długość po przycięciu; 247 + '~' + 6 znaków skrótu = 254. */
    private const val TRUNCATED_SEGMENT_LENGTH = 247

    private const val FORBIDDEN_CHARS = "<>:\"|?*\\"

    private val RESERVED_NAMES: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) {
            add("COM$i")
            add("LPT$i")
        }
    }

    /**
     * Sprawdza postać kanoniczną. Zwraca [PathRejection.NONE], gdy ścieżka nadaje się
     * do składowania.
     */
    fun validate(canonicalPath: String, maxPathBytes: Int = DEFAULT_MAX_PATH_BYTES): PathRejection {
        if (canonicalPath.contains('\u0000')) return PathRejection.NUL
        if (canonicalPath.isEmpty()) return PathRejection.EMPTY_SEGMENT
        if (canonicalPath[0] == '/') return PathRejection.ABSOLUTE

        for (segment in canonicalPath.split('/')) {
            when (segment) {
                "" -> return PathRejection.EMPTY_SEGMENT
                "." -> return PathRejection.DOT_SEGMENT
                ".." -> return PathRejection.TRAVERSAL
            }
        }

        if (canonicalPath.toByteArray(Charsets.UTF_8).size > maxPathBytes) {
            return PathRejection.PATH_TOO_LONG
        }

        return PathRejection.NONE
    }

    /**
     * Zamienia ścieżkę kanoniczną na ścieżkę składowania. Wynik używa separatora '/';
     * zamiana na separator systemowy należy do serwera.
     */
    fun sanitize(canonicalPath: String, maxPathBytes: Int = DEFAULT_MAX_PATH_BYTES): SanitizeResult {
        val rejection = validate(canonicalPath, maxPathBytes)
        if (rejection != PathRejection.NONE) return SanitizeResult.Rejected(rejection)

        val stored = canonicalPath.split('/').joinToString("/") { encodeSegment(it) }
        return SanitizeResult.Success(stored)
    }

    /**
     * Koduje pojedynczą składową. Kolejność reguł jest normatywna
     * (docs/PATH-RULES.md §5) — zmiana kolejności psuje różnowartościowość.
     */
    fun encodeSegment(segment: String): String {
        if (segment.isEmpty()) return segment

        // Reguły 1-3. Jedno przejście wystarcza: '%' trafia do wyjścia już jako %25,
        // więc znaki wprowadzone przez kodowanie nie są kodowane ponownie.
        val builder = StringBuilder(segment.length + 8)
        for (c in segment) {
            if (c == '%' || FORBIDDEN_CHARS.contains(c) || c in '\u0001'..'\u001F') {
                builder.appendEscaped(c)
            } else {
                builder.append(c)
            }
        }
        var encoded = builder.toString()

        // Reguła 5: nazwa zastrzeżona DOS. Sprawdzana na oryginale, bo bierzemy człon
        // przed pierwszą kropką. Pierwszy znak nazwy zastrzeżonej jest zawsze literą,
        // więc nie został zakodowany wyżej i pozycja 0 obu wersji jest tożsama.
        val baseName = segment.substringBefore('.')
        if (baseName.uppercase() in RESERVED_NAMES) {
            encoded = StringBuilder()
                .appendEscaped(encoded[0])
                .append(encoded, 1, encoded.length)
                .toString()
        }

        // Reguła 4: końcowa kropka lub spacja. Kodujemy wyłącznie ostatni znak —
        // wcześniejsze przestają być końcowe, więc Windows ich nie obetnie.
        val last = encoded.last()
        if (last == '.' || last == ' ') {
            encoded = StringBuilder(encoded.substring(0, encoded.length - 1))
                .appendEscaped(last)
                .toString()
        }

        // Reguła 6: limit długości składowej.
        if (encoded.length > MAX_SEGMENT_LENGTH) {
            encoded = truncateAtEscapeBoundary(encoded, TRUNCATED_SEGMENT_LENGTH) +
                "~" + shortHash(segment)
        }

        return encoded
    }

    /**
     * Odwraca kodowanie procentowe. Przyrostka `~xxxxxx` z reguły 6 i z rozstrzygania
     * kolizji wielkości liter nie da się odwrócić bez index.jsonl — ta metoda go nie tyka.
     */
    fun decode(storedPath: String): String {
        val builder = StringBuilder(storedPath.length)
        var i = 0
        while (i < storedPath.length) {
            if (storedPath[i] == '%' &&
                i + 2 < storedPath.length &&
                storedPath[i + 1].isHexDigit() &&
                storedPath[i + 2].isHexDigit()
            ) {
                builder.append(storedPath.substring(i + 1, i + 3).toInt(16).toChar())
                i += 3
            } else {
                builder.append(storedPath[i])
                i++
            }
        }
        return builder.toString()
    }

    /**
     * Przyrostek rozstrzygający kolizję wielkości liter (docs/PATH-RULES.md §6).
     * Deterministyczny: ta sama ścieżka źródłowa zawsze daje ten sam przyrostek,
     * także po odtworzeniu backupu z kopii.
     */
    fun caseCollisionSuffix(canonicalPath: String): String = "~" + shortHash(canonicalPath)

    private fun StringBuilder.appendEscaped(c: Char): StringBuilder =
        append('%').append(c.code.toString(16).uppercase().padStart(2, '0'))

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(3)
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /**
     * Przycina tak, żeby nie rozciąć sekwencji %XX — inaczej kodowanie przestałoby być
     * odwracalne. Każdy '%' w zakodowanym tekście rozpoczyna sekwencję, bo literalne '%'
     * zostało wcześniej zamienione na %25.
     */
    private fun truncateAtEscapeBoundary(encoded: String, limit: Int): String {
        if (encoded.length <= limit) return encoded

        var cut = limit
        for (i in maxOf(0, limit - 2) until limit) {
            if (encoded[i] == '%') {
                cut = i
                break
            }
        }
        return encoded.substring(0, cut)
    }
}
