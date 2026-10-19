package pl.wspa.filum.protocol

import java.io.File

/** One line from docs/testvectors/paths.tsv, with its escapes decoded. */
data class PathVector(val input: String, val expected: String, val note: String) {

    val isRejection: Boolean get() = expected.startsWith("REJECT:")

    val expectedRejection: PathRejection
        get() = when (val reason = expected.removePrefix("REJECT:")) {
            "absolute" -> PathRejection.ABSOLUTE
            "traversal" -> PathRejection.TRAVERSAL
            "dot_segment" -> PathRejection.DOT_SEGMENT
            "empty_segment" -> PathRejection.EMPTY_SEGMENT
            "nul" -> PathRejection.NUL
            "path_too_long" -> PathRejection.PATH_TOO_LONG
            else -> error("unknown rejection reason: $reason")
        }

    // Shown in the test case name; non-printing characters have to be made visible.
    override fun toString(): String = "${visualize(input)} -> ${visualize(expected)}"

    private fun visualize(value: String): String = buildString {
        for (c in value) {
            when {
                c.code < 0x20 -> append("\\x%02X".format(c.code))
                c == ' ' -> append('␣') // a space has to be visible
                else -> append(c)
            }
        }
    }
}

object TestVectors {

    private const val RELATIVE_PATH = "docs/testvectors/paths.tsv"

    fun load(): List<PathVector> {
        val file = locate()

        val vectors = file.readLines(Charsets.UTF_8)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val columns = line.split('\t')
                check(columns.size == 3) {
                    "$RELATIVE_PATH: expected 3 columns, got ${columns.size}: $line"
                }
                PathVector(unescape(columns[0]), unescape(columns[1]), columns[2])
            }

        check(vectors.isNotEmpty()) { "$RELATIVE_PATH contains no vectors" }
        return vectors
    }

    /**
     * Decodes the escapes described in the file header: \xHH is a byte, \\ a literal
     * backslash. Without them, non-printing characters and trailing spaces would not survive
     * in a text file.
     */
    fun unescape(value: String): String {
        val builder = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) {
                if (value[i + 1] == '\\') {
                    builder.append('\\')
                    i += 2
                    continue
                }
                if ((value[i + 1] == 'x' || value[i + 1] == 'X') && i + 3 < value.length) {
                    builder.append(value.substring(i + 2, i + 4).toInt(16).toChar())
                    i += 4
                    continue
                }
            }
            builder.append(value[i])
            i++
        }
        return builder.toString()
    }

    /**
     * Looks for the vector file by walking up from the working directory, so the same
     * logic works no matter which directory Gradle was started from.
     */
    private fun locate(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        var directory: File? = start
        while (directory != null) {
            val candidate = File(directory, RELATIVE_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("$RELATIVE_PATH was not found in any directory above $start")
    }
}
