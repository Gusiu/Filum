package pl.wspa.filum.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer
import okio.source
import pl.wspa.filum.protocol.BundleContentType
import java.io.File
import java.io.IOException

/** One file destined for a bundle. */
data class BundleEntry(
    val canonicalPath: String,
    val file: File,
    val size: Long,
    val mtime: Long,
    /** The digest is required inside a bundle (§5.4); for a small file it costs nothing. */
    val digest: String,
)

/**
 * The body of a bundle of small files (protocol §5.4, ADR-008).
 *
 * Framing: a four-byte header length in network order, the JSON header, then exactly as
 * many content bytes as it announces, and repeat.
 *
 * Everything is streamed straight from disk. A bundle is at most eight megabytes, so
 * collecting one in memory would be no catastrophe — but across several concurrent
 * connections that number starts multiplying for no reason at all.
 */
class BundleBody(private val entries: List<BundleEntry>) : RequestBody() {

    override fun contentType() = BundleContentType.VALUE.toMediaType()

    override fun contentLength(): Long = entries.sumOf { entry ->
        HEADER_LENGTH_BYTES + headerOf(entry).size.toLong() + entry.size
    }

    override fun writeTo(sink: BufferedSink) {
        for (entry in entries) {
            val header = headerOf(entry)

            // The order matters and is part of the protocol: the server reads the length,
            // then the header, then exactly as many bytes as the header announced.
            sink.writeInt(header.size)
            sink.write(header)
            writePayload(sink, entry)
        }
    }

    private fun writePayload(sink: BufferedSink, entry: BundleEntry) {
        entry.file.inputStream().source().buffer().use { source ->
            var remaining = entry.size
            while (remaining > 0) {
                val written = source.read(sink.buffer, minOf(remaining, SEGMENT))
                if (written == -1L) {
                    // The file shrank between the scan and the upload. There is no saving this
                    // mid-bundle — the frame has already announced its length, so aborting the
                    // transfer is the only honest way out.
                    throw IOException("The file shrank while being bundled: ${entry.canonicalPath}")
                }
                remaining -= written
                sink.flush()
            }
        }
    }

    /**
     * The header is built by hand rather than with a serialiser.
     *
     * The path is the only field needing escaping, and its content comes from the phone's
     * filesystem — which is to say it can contain anything. The rest is numbers and a
     * hexadecimal digest.
     */
    private fun headerOf(entry: BundleEntry): ByteArray {
        val json = buildString {
            append("{\"p\":\"")
            appendEscaped(entry.canonicalPath)
            append("\",\"s\":").append(entry.size)
            append(",\"m\":").append(entry.mtime)
            append(",\"d\":\"").append(entry.digest).append("\"}")
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    private fun StringBuilder.appendEscaped(value: String) {
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append("%04x".format(c.code))
                else -> append(c)
            }
        }
    }

    private companion object {
        const val HEADER_LENGTH_BYTES = 4L
        const val SEGMENT = 64L * 1024
    }
}
