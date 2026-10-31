package pl.wspa.filum.app.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Reading a pairing code from a camera frame.
 *
 * The frames are drawn the way the server panel draws the code - error correction level M -
 * and laid out the way a camera delivers luminance: one byte per pixel, rows possibly
 * longer than the image is wide.
 */
class QrDecoderTest {

    private val decoder = QrDecoder()

    /** As long as a real code: a server name, a key fingerprint, a token and three addresses. */
    private val pairingCode = "filum1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
        (
            """{"n":"DESKTOP-TEST","k":"zZVgKtXi_8KPY1BEGM4scorJI-qht3WkL_NeGUzt5uo",""" +
                """"t":"q3Z0mX8kV2nL5pR7sT9uW1yA4bC6dE8f","p":8765,""" +
                """"a":["192.168.1.83:8765","172.28.64.1:8765","100.126.0.1:8765"],"s":"filum-zZVgKtXi"}"""
            ).toByteArray(),
    )

    /** [text] as a QR code filling a square frame of [size] pixels, rows padded to [rowStride]. */
    private fun frame(text: String, size: Int = SIZE, rowStride: Int = size): ByteArray {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
        )
        // The padding is mid-grey, like whatever a camera leaves past the image edge.
        val pixels = ByteArray(rowStride * size) { 0x80.toByte() }
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * rowStride + x] = if (matrix[x, y]) 0 else 0xFF.toByte()
            }
        }
        return pixels
    }

    private fun blank() = ByteArray(SIZE * SIZE) { 0xFF.toByte() }

    @Test
    fun `a pairing code drawn as a QR code reads back exactly`() {
        assertEquals(pairingCode, decoder.decode(frame(pairingCode), SIZE, SIZE))
    }

    @Test
    fun `rows padded beyond the image width do not disturb the reading`() {
        // Cameras round the row length up: 480 pixels may arrive in rows of 512 bytes.
        val padded = frame(pairingCode, rowStride = 512)

        assertEquals(pairingCode, decoder.decode(padded, SIZE, SIZE, rowStride = 512))
    }

    @Test
    fun `a frame with no code in it gives nothing`() {
        assertNull(decoder.decode(blank(), SIZE, SIZE))
    }

    @Test
    fun `half a code gives nothing rather than a guess`() {
        // Only the left half of the frame is handed over - one finder pattern is missing.
        assertNull(decoder.decode(frame(pairingCode), SIZE / 2, SIZE, rowStride = SIZE))
    }

    @Test
    fun `one decoder reads frame after frame`() {
        // The reader keeps state between calls; a frame without a code must not spoil the next.
        decoder.decode(blank(), SIZE, SIZE)

        assertEquals(pairingCode, decoder.decode(frame(pairingCode), SIZE, SIZE))
    }

    private companion object {
        const val SIZE = 480
    }
}
