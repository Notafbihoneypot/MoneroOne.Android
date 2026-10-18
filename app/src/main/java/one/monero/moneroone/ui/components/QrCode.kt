package one.monero.moneroone.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.ByteMatrix
import com.google.zxing.qrcode.encoder.Encoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import one.monero.moneroone.R
import one.monero.moneroone.ui.theme.MoneroTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rounded QR code as a bitmap: the modules alone, no quiet zone, [modules]
 * wide. [logoModules] is the width of the cleared center the logo goes in, 0
 * for a code without a logo.
 */
class QrBitmap(val bitmap: Bitmap, val modules: Int, val logoModules: Int)

/**
 * The look of the rounded code (iOS QRStyle and QRStyledCode, the same
 * numbers): round dots, finder patterns with rounded corners, and a round
 * cleared center for the logo, in the style of Cash App's codes. Lengths are
 * in modules (one module = one cell of the code).
 */
object QrStyle {
    /** Data, timing and alignment modules: filled circles this wide. */
    const val DOT_DIAMETER = 0.8

    // The finder patterns keep their exact cells: a 7 x 7 ring 1 module
    // thick, a 1-module light gap, a 3 x 3 center. Only the corners round.

    /** Corner radius of the ring's outer edge. */
    const val FINDER_OUTER_RADIUS = 1.5
    /** Corner radius of the ring's inner edge (the 5 x 5 hole). */
    const val FINDER_INNER_RADIUS = 0.75
    /** Corner radius of the 3 x 3 center. */
    const val FINDER_CENTER_RADIUS = 0.25

    /** The logo's width as a fraction of the code's width, quiet zone not included. */
    const val LOGO_FRACTION = 0.28
    /** Empty space around the logo: cells closer than this to its edge are cleared. */
    const val LOGO_PADDING = 1.0

    /** The smallest code that gets a logo: version 5 (37 modules). */
    const val MINIMUM_LOGO_CODE_SIZE = 37

    /** White around the code on its plate, in modules. */
    const val QUIET_ZONE = 4

    /** The logo's width in modules: the odd number nearest to [LOGO_FRACTION] of the code, or 0 (no logo). */
    fun logoModules(size: Int): Int {
        if (LOGO_FRACTION <= 0.0 || size < MINIMUM_LOGO_CODE_SIZE) return 0
        val exact = LOGO_FRACTION * size
        val odd = ((exact - 1) / 2).roundToInt() * 2 + 1
        return max(1, min(odd, size - 16))
    }

    /** True when the logo clears this cell: the cell comes closer than the padding to the round logo's edge. */
    fun isCleared(row: Int, column: Int, size: Int, logoModules: Int): Boolean {
        if (logoModules <= 0) return false
        val center = size / 2.0
        // The cell's nearest point to the code's center, per axis.
        val dx = max(abs(column + 0.5 - center) - 0.5, 0.0)
        val dy = max(abs(row + 0.5 - center) - 0.5, 0.0)
        val reach = logoModules / 2.0 + LOGO_PADDING
        return dx * dx + dy * dy < reach * reach
    }

    /** True for the cells of the three 7 x 7 finder patterns. */
    fun isFinder(row: Int, column: Int, size: Int): Boolean {
        val far = size - 7
        return (row < 7 && column < 7) || (row < 7 && column >= far) || (row >= far && column < 7)
    }

    /**
     * Draws the dark shapes of [matrix] in black into a [side] x [side]
     * square at the canvas origin, no quiet zone, with the logo's area left
     * empty.
     */
    fun draw(canvas: Canvas, matrix: ByteMatrix, side: Float, logoModules: Int, paint: Paint) {
        val n = matrix.width
        val module = side / n
        val dot = DOT_DIAMETER.coerceIn(0.1, 1.0).toFloat() * module
        for (row in 0 until n) {
            for (column in 0 until n) {
                if (matrix[column, row].toInt() != 1) continue
                if (isFinder(row, column, n) || isCleared(row, column, n, logoModules)) continue
                canvas.drawCircle((column + 0.5f) * module, (row + 0.5f) * module, dot / 2f, paint)
            }
        }
        // A rounded square with a rounded hole and a rounded center: even-odd
        // fill makes the ring.
        val finders = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for ((row, column) in listOf(0 to 0, 0 to n - 7, n - 7 to 0)) {
            val outer = RectF(column * module, row * module, (column + 7) * module, (row + 7) * module)
            finders.addRoundedSquare(outer, FINDER_OUTER_RADIUS.toFloat() * module)
            finders.addRoundedSquare(outer.insetBy(module), FINDER_INNER_RADIUS.toFloat() * module)
            finders.addRoundedSquare(outer.insetBy(2 * module), FINDER_CENTER_RADIUS.toFloat() * module)
        }
        canvas.drawPath(finders, paint)
    }

    private fun RectF.insetBy(d: Float) = RectF(left + d, top + d, right - d, bottom - d)

    private fun Path.addRoundedSquare(rect: RectF, radius: Float) {
        val r = radius.coerceIn(0f, rect.width() / 2f)
        if (r > 0f) addRoundRect(rect, r, r, Path.Direction.CW) else addRect(rect, Path.Direction.CW)
    }
}

/**
 * Rounded QR codes (iOS QRStyledCode): error correction Q, drawn with many
 * pixels per module and shown scaled down with filtering. The shapes keep
 * clean edges at any size and glide while focus mode grows the code.
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
        // The modules alone: the encoder adds no margin.
        val matrix = try {
            Encoder.encode(content, ErrorCorrectionLevel.Q, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
        } catch (e: Exception) {
            return null
        }
        val modules = matrix.width
        val logoModules = QrStyle.logoModules(modules)
        val side = modules * ceil(BITMAP_WIDTH.toDouble() / modules).toInt()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8)
        QrStyle.draw(Canvas(bitmap), matrix, side.toFloat(), logoModules,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK })
        bitmap.setHasMipMap(true)
        bitmap.prepareToDraw()
        return QrBitmap(bitmap, modules, logoModules).also { cache.put(content, it) }
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

    /**
     * The code filling [rect] and the mark in its cleared center (iOS
     * QRStyledCode): the mark is as wide as the logo's modules, with no disc
     * behind it.
     */
    fun drawCode(canvas: Canvas, code: QrBitmap, logo: Bitmap?, rect: RectF) {
        canvas.drawBitmap(code.bitmap, null, rect, codePaint)
        if (logo != null && code.logoModules > 0) {
            val half = rect.width() * code.logoModules / code.modules / 2f
            canvas.drawBitmap(logo, null,
                RectF(rect.centerX() - half, rect.centerY() - half, rect.centerX() + half, rect.centerY() + half), imagePaint)
        }
    }

    /**
     * A code on its white plate (iOS QRPlate): four modules of quiet zone,
     * corners that round as it grows,
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
            val quiet = side * QrStyle.QUIET_ZONE / (code.modules + 2 * QrStyle.QUIET_ZONE)
            drawCode(canvas, code, logo, RectF(rect.left + quiet, rect.top + quiet, rect.right - quiet, rect.bottom - quiet))
        }
    }

    /**
     * The image Save to Photos and Share QR Code hand out (iOS
     * QRCodeRenderer): the code with a one-module margin and the logo,
     * 1200 px square, on white.
     */
    fun image(context: Context, content: String, size: Int = 1200): Bitmap? {
        val code = bitmap(content) ?: return null
        val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(android.graphics.Color.WHITE)
        val margin = size.toFloat() / (code.modules + 2)
        drawCode(canvas, code, logo(context), RectF(margin, margin, size - margin, size - margin))
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
