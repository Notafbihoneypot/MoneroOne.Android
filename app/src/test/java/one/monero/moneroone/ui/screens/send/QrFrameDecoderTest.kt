package one.monero.moneroone.ui.screens.send

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class QrFrameDecoderTest {

    // Monero General Fund donation address (public).
    private val uri = "monero:888tNkZrPN6JsEgekjMnABU4TBzc2Dt29EPAvkRxbANsAnjyPbb3iQ1YBRk1UXcdRsiKc9dhwMVgN5S9cQUiyoogDavup3H" +
        "?tx_amount=0.25&recipient_name=Test"

    // CameraX's default analysis size, with padded rows as many camera HALs deliver them.
    private val width = 640
    private val height = 480
    private val rowStride = 704

    @Test fun decodesMoneroUriFromCameraLumaPlane() {
        val buffer = cameraLumaPlane(qr(uri), darkOnLight = true)
        val luma = QrFrameDecoder.copyLumaPlane(buffer, rowStride, height)
        assertEquals(uri, QrFrameDecoder.decode(luma, rowStride, width, height))
    }

    @Test fun decodesLightCodeOnDarkBackground() {
        val buffer = cameraLumaPlane(qr(uri), darkOnLight = false)
        val luma = QrFrameDecoder.copyLumaPlane(buffer, rowStride, height)
        assertEquals(uri, QrFrameDecoder.decode(luma, rowStride, width, height))
    }

    @Test fun frameWithoutCodeReturnsNull() {
        val buffer = ByteBuffer.wrap(ByteArray(rowStride * (height - 1) + width) { 128.toByte() })
        val luma = QrFrameDecoder.copyLumaPlane(buffer, rowStride, height)
        assertNull(QrFrameDecoder.decode(luma, rowStride, width, height))
    }

    private fun qr(text: String): BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))

    /**
     * Builds a Y plane the way CameraX hands it to the analyzer: rows of [rowStride] bytes, with
     * the last row cut at the image width. The code is drawn 4 pixels per module in the middle,
     * with camera-like levels (16 for dark, 235 for light) and the row padding filled with noise.
     */
    private fun cameraLumaPlane(matrix: BitMatrix, darkOnLight: Boolean): ByteBuffer {
        val dark = 16.toByte()
        val light = 235.toByte()
        val scale = 4
        val codeSize = matrix.width * scale
        val left = (width - codeSize) / 2
        val top = (height - codeSize) / 2
        val bytes = ByteArray(rowStride * (height - 1) + width)
        for (y in 0 until height) {
            for (x in 0 until rowStride) {
                val i = y * rowStride + x
                if (i >= bytes.size) break
                if (x >= width) {
                    bytes[i] = ((x * 31 + y * 17) and 0xFF).toByte()
                    continue
                }
                val inCode = x in left until left + codeSize && y in top until top + codeSize
                val module = inCode && matrix[(x - left) / scale, (y - top) / scale]
                bytes[i] = if (module == darkOnLight) dark else light
            }
        }
        return ByteBuffer.wrap(bytes)
    }
}
