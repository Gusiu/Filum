package pl.wspa.filum.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer
import okio.source
import pl.wspa.filum.protocol.BundleContentType
import java.io.File
import java.io.IOException

/** Jeden plik przeznaczony do wysłania w paczce. */
data class BundleEntry(
    val canonicalPath: String,
    val file: File,
    val size: Long,
    val mtime: Long,
    /** Skrót jest w paczce wymagany (§5.4); dla małego pliku kosztuje tyle co nic. */
    val digest: String,
)

/**
 * Ciało paczki małych plików (§5.4 protokołu, ADR-008).
 *
 * Ramkowanie: czterobajtowa długość nagłówka w porządku sieciowym, nagłówek JSON,
 * dokładnie tyle bajtów zawartości, ile zapowiada, i tak w kółko.
 *
 * Wszystko jest strumieniowane wprost z dysku. Paczka ma do ośmiu megabajtów, więc
 * zebranie jej w pamięci nie byłoby katastrofą — ale przy kilku równoległych
 * połączeniach ta liczba zaczyna się mnożyć bez żadnego powodu.
 */
class BundleBody(private val entries: List<BundleEntry>) : RequestBody() {

    override fun contentType() = BundleContentType.VALUE.toMediaType()

    override fun contentLength(): Long = entries.sumOf { entry ->
        HEADER_LENGTH_BYTES + headerOf(entry).size.toLong() + entry.size
    }

    override fun writeTo(sink: BufferedSink) {
        for (entry in entries) {
            val header = headerOf(entry)

            // Kolejność ma znaczenie i jest częścią protokołu: serwer czyta długość,
            // potem nagłówek, potem dokładnie tyle bajtów, ile nagłówek zapowiada.
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
                    // Plik skrócił się między skanem a wysyłką. Nie da się tego uratować
                    // w środku paczki — ramka już zapowiedziała swoją długość, więc
                    // przerwanie transferu jest jedynym uczciwym wyjściem.
                    throw IOException("Plik skrócił się w trakcie pakowania: ${entry.canonicalPath}")
                }
                remaining -= written
                sink.flush()
            }
        }
    }

    /**
     * Nagłówek budujemy ręcznie, a nie serializatorem.
     *
     * Ścieżka jest jedynym polem wymagającym ucieczki, a jej treść pochodzi
     * z systemu plików telefonu — czyli może zawierać cokolwiek. Reszta to liczby
     * i szesnastkowy skrót.
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
