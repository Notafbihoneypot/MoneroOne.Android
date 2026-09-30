package one.monero.moneroone.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.monero.moneroone.R
import one.monero.moneroone.ui.theme.MoneroTheme
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A QR code bitmap and its width in modules, the one-module margin included
 * (iOS QRBitmap: CoreImage draws the same margin).
 */
class QrBitmap(val bitmap: Bitmap, val modules: Int)

/**
 * QR codes as iOS QRCodeView draws them: error correction H, so the logo can
 * cover the center, and many pixels per module, drawn scaled down with
 * filtering. The modules keep clean edges at any size and glide while focus
 * mode grows the code; one pixel per module scaled up without filtering
 * jumps a pixel at a time mid-grow.
 */
object QrCodes {
    /** At least this many pixels wide: the largest focus code (448 dp) at 3x, so a code is only scaled down. */
    private const val BITMAP_WIDTH = 1344

    /**
     * One bitmap per text: a code never changes while it grows, and a new
     * amount does not rebuild the code of the old one. Alpha only (the
     * modules), about 1.8 MB each.
     */
    private val cache = object : LruCache<String, QrBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: QrBitmap) = value.bitmap.byteCount
    }

    fun cached(content: String): QrBitmap? = cache.get(content)

    /** The code for [content], or null when the text does not fit in a QR code. Call off the main thread. */
    fun bitmap(content: String): QrBitmap? {
        cache.get(content)?.let { return it }
        val matrix = try {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H
            ))
        } catch (e: Exception) {
            return null
        }
        // Width 0 draws one pixel per module, the margin included.
        val modules = matrix.width
        val scale = ceil(BITMAP_WIDTH.toDouble() / modules).toInt()
        val side = modules * scale
        val pixels = ByteArray(side * side)
        for (y in 0 until modules) {
            val row = ByteArray(side)
            for (x in 0 until modules) {
                if (matrix[x, y]) row.fill(0xFF.toByte(), x * scale, (x + 1) * scale)
            }
            for (i in 0 until scale) System.arraycopy(row, 0, pixels, (y * scale + i) * side, side)
        }
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        bitmap.setHasMipMap(true)
        bitmap.prepareToDraw()
        return QrBitmap(bitmap, modules).also { cache.put(content, it) }
    }

    @Volatile private var logo: Bitmap? = null

    /** The mark once [logo] has decoded it. */
    val cachedLogo: Bitmap? get() = logo

    /** The flat mark iOS draws in its codes (MoneroLogo), decoded once at 512 px. Call off the main thread. */
    fun logo(context: Context): Bitmap? = logo ?: synchronized(this) {
        logo ?: BitmapFactory.decodeResource(context.resources, R.drawable.monero_qr_logo,
            BitmapFactory.Options().apply { inSampleSize = 2 })
            ?.also { it.setHasMipMap(true); it.prepareToDraw(); logo = it }
    }

    private val codePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.BLACK }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }

    /**
     * The code filling [rect] and the logo on a white disc at its center
     * (iOS QRCodeView): the disc is 22% of the code, the mark inset 10%.
     */
    fun drawCode(canvas: Canvas, code: QrBitmap, logo: Bitmap?, rect: RectF) {
        canvas.drawBitmap(code.bitmap, null, rect, codePaint)
        val disc = rect.width() * 0.22f
        canvas.drawCircle(rect.centerX(), rect.centerY(), disc / 2f, whitePaint)
        if (logo != null) {
            val half = disc * 0.8f / 2f
            canvas.drawBitmap(logo, null,
                RectF(rect.centerX() - half, rect.centerY() - half, rect.centerX() + half, rect.centerY() + half), imagePaint)
        }
    }

    /**
     * A code on its white plate (iOS QRPlate): four modules of quiet zone
     * (the code's own margin plus three), corners that round as it grows,
     * the shadow inside the plate so a growing copy looks exactly like the
     * code it starts from, and a hairline edge in light mode. [code] null
     * draws the plate alone, while the code is made.
     */
    fun drawPlate(
        canvas: Canvas, code: QrBitmap?, logo: Bitmap?, rect: RectF, density: Float,
        castsShadow: Boolean, border: Boolean
    ) {
        val side = rect.width()
        if (side <= 0f) return
        val radius = min(20f * density, max(12f * density, side * 0.045f))
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            // iOS: black at 10%, radius 10. Skia's blur sigma is 0.57735 r + 0.5.
            if (castsShadow) setShadowLayer((10f * density - 0.5f) / 0.57735f, 0f, 0f, 0x1A000000)
        }
        canvas.drawRoundRect(rect, radius, radius, plate)
        if (border) {
            val inset = density / 2f
            canvas.drawRoundRect(RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
                radius - inset, radius - inset,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = density
                    color = 0x0F000000
                })
        }
        if (code != null) {
            val quiet = side * 3f / (code.modules + 6)
            drawCode(canvas, code, logo, RectF(rect.left + quiet, rect.top + quiet, rect.right - quiet, rect.bottom - quiet))
        }
    }

    /**
     * The image Save to Photos and Share QR Code hand out (iOS
     * QRCodeRenderer): the code with its one-module margin and the logo,
     * 1200 px square, on white.
     */
    fun image(context: Context, content: String, size: Int = 1200): Bitmap? {
        val code = bitmap(content) ?: return null
        val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(android.graphics.Color.WHITE)
        drawCode(canvas, code, logo(context), RectF(0f, 0f, size.toFloat(), size.toFloat()))
        return image
    }
}

/**
 * The code for [content] once it and the logo are made off the main thread;
 * null until then. Never the code of an earlier text: the plate stays blank
 * instead.
 */
@Composable
fun rememberQrBitmap(content: String): QrBitmap? {
    val context = LocalContext.current.applicationContext
    var made by remember { mutableStateOf<Pair<String, QrBitmap?>?>(null) }
    val ready = QrCodes.cached(content)?.takeIf { QrCodes.cachedLogo != null }
    LaunchedEffect(content) {
        if (ready == null && content.isNotEmpty()) {
            made = content to withContext(Dispatchers.Default) {
                QrCodes.logo(context)
                QrCodes.bitmap(content)
            }
        }
    }
    return ready ?: made?.takeIf { it.first == content }?.second
}

/** [QrCodes.drawPlate] at [side], white in light and dark mode like iOS. */
@Composable
fun QrPlate(content: String, side: Dp, modifier: Modifier = Modifier, castsShadow: Boolean = true) {
    val code = rememberQrBitmap(content)
    val border = !MoneroTheme.isDark
    Spacer(
        modifier.size(side).drawBehind {
            drawIntoCanvas {
                QrCodes.drawPlate(it.nativeCanvas, code, QrCodes.cachedLogo, RectF(0f, 0f, size.width, size.height),
                    density, castsShadow, border)
            }
        }
    )
}
