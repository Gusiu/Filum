package pl.wspa.filum.app.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finds a QR code in one camera frame.
 *
 * It works on the luminance plane alone - the Y of a YUV frame - which is all a QR code
 * needs, and that keeps it free of Android, so it is tested on the JVM. One instance
 * serves one thread: the ZXing reader underneath keeps state between calls.
 */
class QrDecoder {

    private val reader = QRCodeReader()

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        // A phone held up to a monitor sees the code small, slanted and with moiré;
        // the slower search is what makes that readable at all.
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * @param luminance one byte per pixel, [rowStride] bytes per row - a camera pads its
     *   rows beyond the image width, so the stride is not simply [width].
     * @return the text of the code, or null when the frame holds no readable one.
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)

        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (e: NotFoundException) {
            null
        } catch (e: ChecksumException) {
            null
        } catch (e: FormatException) {
            null
        } finally {
            reader.reset()
        }
    }
}
