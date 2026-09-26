package one.monero.moneroone.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import one.monero.moneroone.MainActivity
import one.monero.moneroone.R
import one.monero.moneroone.data.util.ChartMath
import one.monero.moneroone.ui.screens.chart.ChartDateFormats
import one.monero.moneroone.ui.screens.chart.ChartTimeAxis
import java.text.NumberFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

class PriceWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        PriceUpdateWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        PriceUpdateWorker.cancel(context)
    }

    companion object {
        private const val ORANGE = 0xFFFF6600.toInt()
        private const val X_LABEL_INSET = 4f
        private const val X_LABEL_GAP = 8f

        private enum class Size { SMALL, MEDIUM, LARGE }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PriceWidgetReceiver::class.java))
            for (id in ids) {
                updateWidget(context, manager, id)
            }
        }

        /**
         * What the widget shows. The line and the high/low were saved with
         * the currency they are in; under a price in another currency they
         * are left out until the app saves them again.
         */
        private class Snapshot(
            val price: Float,
            val change: Float,
            val symbol: String,
            /** One value per half-hour slot, oldest first, the last at [sparklineEndMs]. */
            val sparkline: List<Double>,
            val sparklineEndMs: Long,
            val high: Double?,
            val low: Double?,
            val updatedAt: Long
        )

        private fun load(context: Context): Snapshot {
            val sparklineCurrency = WidgetDataStore.getSparklineCurrencyCode(context)
            val sameCurrency = sparklineCurrency != null && sparklineCurrency == WidgetDataStore.getCurrencyCode(context)
            return Snapshot(
                price = WidgetDataStore.getPrice(context),
                change = WidgetDataStore.getChange24h(context),
                symbol = WidgetDataStore.getCurrencySymbol(context),
                sparkline = if (sameCurrency) WidgetDataStore.getSparkline(context) else emptyList(),
                sparklineEndMs = WidgetDataStore.getSparklineEnd(context),
                high = if (sameCurrency) WidgetDataStore.getHigh24h(context) else null,
                low = if (sameCurrency) WidgetDataStore.getLow24h(context) else null,
                updatedAt = WidgetDataStore.getPriceUpdatedAt(context)
            )
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val data = load(context)

            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: provide all three layouts; system picks based on actual size.
                // Cutoffs match iOS widget families: 2x2 small, 4x2 medium, 4x4 large.
                val small = buildView(context, Size.SMALL, data)
                val medium = buildView(context, Size.MEDIUM, data)
                val large = buildView(context, Size.LARGE, data)
                RemoteViews(
                    mapOf(
                        SizeF(110f, 110f) to small,
                        SizeF(240f, 110f) to medium,
                        SizeF(240f, 240f) to large
                    )
                )
            } else {
                val opts = manager.getAppWidgetOptions(widgetId)
                val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                val size = when {
                    w >= 240 && h >= 240 -> Size.LARGE
                    w >= 240 -> Size.MEDIUM
                    else -> Size.SMALL
                }
                buildView(context, size, data)
            }

            manager.updateAppWidget(widgetId, views)
        }

        private fun buildView(context: Context, size: Size, data: Snapshot): RemoteViews {
            val price = data.price
            val symbol = data.symbol
            // The API's 24h change, as on iOS.
            val change = data.change
            val layoutId = when (size) {
                Size.LARGE -> R.layout.widget_price_large
                Size.MEDIUM -> R.layout.widget_price_medium
                Size.SMALL -> R.layout.widget_price
            }
            val views = RemoteViews(context.packageName, layoutId)

            val logoPx = when (size) {
                Size.SMALL -> 36
                Size.MEDIUM -> 24
                Size.LARGE -> 28
            }
            views.setImageViewBitmap(R.id.price_logo, WidgetUtils.getCircularLogo(context, logoPx))

            if (price > 0) {
                val format = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
                    minimumFractionDigits = 2
                    maximumFractionDigits = 2
                }
                views.setTextViewText(R.id.price_value, "$symbol${format.format(price)}")

                val sign = if (change >= 0) "+" else ""
                views.setTextViewText(R.id.price_change, " $sign${String.format("%.2f", change)}% ")
                val changeColor = if (change >= 0) 0xFF34C759.toInt() else 0xFFFF3B30.toInt()
                val badgeBg = if (change >= 0) R.drawable.widget_badge_green else R.drawable.widget_badge_red
                views.setTextColor(R.id.price_change, changeColor)
                views.setInt(R.id.price_change, "setBackgroundResource", badgeBg)
                views.setViewVisibility(R.id.price_change, View.VISIBLE)
            } else {
                views.setTextViewText(R.id.price_value, "Open Monero One")
                views.setViewVisibility(R.id.price_change, View.GONE)
            }

            val points = data.sparkline
            if (size != Size.SMALL && points.size > 1) {
                val bitmap = if (size == Size.LARGE) {
                    renderFullChart(context, points, data.sparklineEndMs, symbol, 800, 600)
                } else {
                    renderSparkline(points, 400, 200)
                }
                views.setImageViewBitmap(R.id.price_chart, bitmap)
            }

            // The high and low of every 24h sample, not of the half-hour slots.
            val high = data.high
            val low = data.low
            if (size == Size.MEDIUM && price > 0 && high != null && low != null) {
                val compact = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
                    maximumFractionDigits = 0
                }
                views.setTextViewText(
                    R.id.price_hilo,
                    "↑ $symbol${compact.format(high)}   ↓ $symbol${compact.format(low)}"
                )
                views.setViewVisibility(R.id.price_hilo, View.VISIBLE)
            }

            if (size == Size.LARGE && data.updatedAt > 0) {
                val ago = formatRelative(System.currentTimeMillis() - data.updatedAt)
                views.setTextViewText(R.id.price_updated, "Updated $ago ago")
                views.setViewVisibility(R.id.price_updated, View.VISIBLE)
            }

            val intent = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_price_root, pending)

            return views
        }

        private fun formatRelative(deltaMs: Long): String {
            val seconds = deltaMs / 1000
            return when {
                seconds < 60 -> "${seconds.coerceAtLeast(1)}s"
                seconds < 3600 -> "${seconds / 60}m"
                seconds < 86400 -> "${seconds / 3600}h"
                else -> "${seconds / 86400}d"
            }
        }

        /**
         * [points] are half-hour slots, the last at [endMs]. X labels sit at
         * the in-app 24H chart's hours (12 AM, 5 AM, 10 AM, 3 PM, 8 PM) at
         * the time each one really is on the line.
         */
        private fun renderFullChart(context: Context, points: List<Double>, endMs: Long, symbol: String, width: Int, height: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Reserve gutters for axis labels
            val rightGutter = 70f   // Y-axis price labels on right
            val bottomGutter = 30f  // X-axis time labels at bottom
            val topGutter = 18f     // headroom so the top Y-label isn't clipped
            val plotLeft = 0f
            val plotTop = topGutter
            val plotRight = width - rightGutter
            val plotBottom = height - bottomGutter
            val plotW = plotRight - plotLeft
            val plotH = plotBottom - plotTop

            val min = points.min()
            val max = points.max()
            val range = if (max - min > 0) max - min else 1.0
            val padding = range * 0.05
            val yMin = min - padding
            val yMax = max + padding
            val yRange = yMax - yMin

            val yLabelPaint = Paint().apply {
                color = 0xFF8E8E93.toInt()
                textSize = 22f
                isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
                textAlign = Paint.Align.LEFT
            }
            val xLabelPaint = Paint().apply {
                color = 0xFF8E8E93.toInt()
                textSize = 22f
                isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
                textAlign = Paint.Align.LEFT
            }
            val gridPaint = Paint().apply {
                color = 0x408E8E93.toInt()
                strokeWidth = 1f
                isAntiAlias = true
            }

            // Y-axis: 3 labels (max, mid, min) with grid lines
            val yValues = listOf(yMax, (yMin + yMax) / 2, yMin)
            for (yv in yValues) {
                val y = (plotTop + (1.0 - (yv - yMin) / yRange) * plotH).toFloat()
                canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
                val label = "$symbol${yv.roundToLong()}"
                canvas.drawText(label, plotRight + 6f, y + yLabelPaint.textSize / 3f, yLabelPaint)
            }

            // X-axis: a gridline at each tick; a label starts at its line
            // and is left out where it would run into the one before it or
            // past the plot.
            if (endMs > 0) {
                val startMs = endMs - (points.size - 1) * ChartMath.SPARKLINE_SLOT_MS
                val locale = context.resources.configuration.locales[0]
                val timeZone = TimeZone.getDefault()
                val formats = ChartDateFormats(locale, timeZone, DateFormat.is24HourFormat(context)) { skeleton ->
                    DateFormat.getBestDateTimePattern(locale, skeleton)
                }
                var lastEnd = Float.NEGATIVE_INFINITY
                for (tick in ChartTimeAxis.DAY.ticks(startMs, endMs, timeZone, locale)) {
                    val x = plotLeft + (tick - startMs).toFloat() / (endMs - startMs) * plotW
                    canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
                    val label = formats.tickLabel(ChartTimeAxis.DAY, tick)
                    val left = x + X_LABEL_INSET
                    val right = left + xLabelPaint.measureText(label)
                    if (left < lastEnd + X_LABEL_GAP || right > plotRight) continue
                    canvas.drawText(label, left, plotBottom + 22f, xLabelPaint)
                    lastEnd = right
                }
            }

            // Sparkline (line + gradient fill)
            val linePaint = Paint().apply {
                color = ORANGE
                strokeWidth = 4f
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val fillPaint = Paint().apply {
                shader = LinearGradient(
                    0f, plotTop, 0f, plotBottom,
                    (ORANGE and 0x00FFFFFF) or 0x66000000,
                    0x00000000,
                    Shader.TileMode.CLAMP
                )
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            val linePath = Path()
            val fillPath = Path()
            val stepX = plotW / (points.size - 1)
            points.forEachIndexed { i, v ->
                val x = plotLeft + i * stepX
                val y = (plotTop + (1.0 - (v - yMin) / yRange) * plotH).toFloat()
                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, plotBottom)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(plotRight, plotBottom)
            fillPath.close()

            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)

            return bitmap
        }

        private fun renderSparkline(points: List<Double>, width: Int, height: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val min = points.min()
            val max = points.max()
            val range = if (max - min > 0) max - min else 1.0
            val padding = range * 0.05

            val linePaint = Paint().apply {
                color = ORANGE
                strokeWidth = 4f
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            val fillPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    (ORANGE and 0x00FFFFFF) or 0x66000000, // 40% alpha orange
                    0x00000000, // transparent
                    Shader.TileMode.CLAMP
                )
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            val linePath = Path()
            val fillPath = Path()

            val stepX = width.toFloat() / (points.size - 1)

            points.forEachIndexed { i, value ->
                val x = i * stepX
                val y = height - ((value - min + padding) / (range + 2 * padding) * height).toFloat()

                if (i == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, height.toFloat())
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            // Close fill path
            fillPath.lineTo(width.toFloat(), height.toFloat())
            fillPath.close()

            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)

            return bitmap
        }
    }
}
