package pl.wspa.filum.protocol

import java.security.MessageDigest

/** Why a path was rejected. Corresponds to the `error` field in an HTTP 422 response. */
enum class PathRejection {
    NONE,
    ABSOLUTE,
    TRAVERSAL,
    DOT_SEGMENT,
    EMPTY_SEGMENT,
    NUL,
    PATH_TOO_LONG,
}

/** The result of sanitisation: either a storage path or a reason for rejection. */
sealed interface SanitizeResult {
    data class Success(val stored: String) : SanitizeResult

    data class Rejected(val reason: PathRejection) : SanitizeResult
}

/**
 * Maps a canonical path from the phone onto a storage path under NTFS.
 *
 * Implementacja normatywna dla docs/PATH-RULES.md. Lustrzanym odbiciem jest klasa
 * `Filum.Protocol.PathRules` on the server side - both pass the same
 * docs/testvectors/paths.tsv.
 *
 * The module is plain Kotlin on the JVM with no Android dependency: the rules can be
 * tested without an emulator, and a mistake here costs a file silently destroyed.
 */
object PathRules {

    const val DEFAULT_MAX_PATH_BYTES: Int = 4096

    /** The NTFS limit on a single path component, in UTF-16 units. */
    private const val MAX_SEGMENT_LENGTH = 255

    /** Target length after truncation; 247 + '~' + 6 digest characters = 254. */
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
     * Checks the canonical form. Returns [PathRejection.NONE] when the path is fit for
     * storage.
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
     * Turns a canonical path into a storage path. The result uses '/' as its separator;
     * converting to the platform separator is the server's job.
     */
    fun sanitize(canonicalPath: String, maxPathBytes: Int = DEFAULT_MAX_PATH_BYTES): SanitizeResult {
        val rejection = validate(canonicalPath, maxPathBytes)
        if (rejection != PathRejection.NONE) return SanitizeResult.Rejected(rejection)

        val stored = canonicalPath.split('/').joinToString("/") { encodeSegment(it) }
        return SanitizeResult.Success(stored)
    }

    /**
     * Encodes a single component. The order of the rules is normative
     * (docs/PATH-RULES.md §5) - reordering them breaks injectivity.
     */
    fun encodeSegment(segment: String): String {
        if (segment.isEmpty()) return segment

        // Rules 1-3. A single pass is enough: '%' reaches the output already as %25, so
        // the characters the encoding introduces are not encoded a second time.
        val builder = StringBuilder(segment.length + 8)
        for (c in segment) {
            if (c == '%' || FORBIDDEN_CHARS.contains(c) || c in '\u0001'..'\u001F') {
                builder.appendEscaped(c)
            } else {
                builder.append(c)
            }
        }
        var encoded = builder.toString()

        // Rule 5: a DOS reserved name. Checked against the original, because we take the
        // part before the first dot. The first character of a reserved name is always a
        // letter, so it was not encoded above and position 0 is the same in both forms.
        val baseName = segment.substringBefore('.')
        if (baseName.uppercase() in RESERVED_NAMES) {
            encoded = StringBuilder()
                .appendEscaped(encoded[0])
                .append(encoded, 1, encoded.length)
                .toString()
        }

        // Rule 4: a trailing dot or space. We encode only the last character - the
        // earlier ones stop being trailing, so Windows will not trim them.
        val last = encoded.last()
        if (last == '.' || last == ' ') {
            encoded = StringBuilder(encoded.substring(0, encoded.length - 1))
                .appendEscaped(last)
                .toString()
        }

        // Rule 6: the component length limit.
        if (encoded.length > MAX_SEGMENT_LENGTH) {
            encoded = truncateAtEscapeBoundary(encoded, TRUNCATED_SEGMENT_LENGTH) +
                "~" + shortHash(segment)
        }

        return encoded
    }

    /**
     * Reverses the percent-encoding. The `~xxxxxx` suffix from rule 6 and from case-collision
     * resolution cannot be reversed without index.jsonl - this method leaves it alone.
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
     * The suffix that resolves a case collision (docs/PATH-RULES.md §6). Deterministic: the
     * same source path always yields the same suffix, including after the backup has been
     * restored from a copy.
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
     * Truncates without splitting a %XX sequence - otherwise the encoding would stop being
     * reversible. Every '%' in the encoded text starts a sequence, because a literal '%'
     * was turned into %25 earlier.
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
