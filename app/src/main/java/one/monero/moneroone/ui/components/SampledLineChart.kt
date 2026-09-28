package one.monero.moneroone.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import one.monero.moneroone.data.util.MoneyFormat
import one.monero.moneroone.data.util.nearestIndexByTimestamp
import one.monero.moneroone.ui.screens.chart.ChartDateFormats
import one.monero.moneroone.ui.theme.MoneroOrange
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.SuccessGreen
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * One series drawn as a line and fill over every real sample, with a
 * touch-and-drag scrub. The selection is state inside the chart, reported
 * up through [onSelect] as an index into [points]; a scrub redraws the
 * indicator layer only, never the line. The line, labels and paths are
 * rebuilt only when [points], [domain] or [axes] change, which keeps a few
 * hundred samples smooth without dropping any. Matches iOS
 * SampledLineChart.
 *
 * Touch or drag to read a sample; lifting clears it. Tapping a marker keeps
 * its sample selected until the next tap. The first clear move of a touch
 * decides its axis: mostly vertical means the page is scrolling and the
 * touch is ignored until it ends; anything else scrubs.
 *
 * TalkBack sees one node: the label names the chart, the state sums up the
 * line, and with markers the custom actions step through them, pinning each
 * as a tap would, so the header shows it too.
 *
 * Callers key their own selection on the same [points] instance: new points
 * clear the selection here without a call to [onSelect].
 */
@Composable
fun SampledLineChart(
    points: List<ChartPoint>,
    domain: ClosedFloatingPointRange<Double>,
    axes: ChartAxes?,
    speech: ChartSpeech,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    markers: List<ChartMarker> = emptyList(),
    axisLabelWidth: Dp? = null
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // Axis labels: caption2 (tokens.json type.roles).
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val labelWidth = with(density) { axisLabelWidth?.toPx()?.times(fontScale) }
    val layout = remember(points, domain, axes, markers, labelStyle, textMeasurer, density, labelWidth) {
        ChartLayout.of(points, domain, axes, markers, textMeasurer, labelStyle, with(density) { LABEL_GAP.toPx() }, labelWidth)
    }

    val gridColor = MoneroTheme.colors.separator
    val indicatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val orange = MoneroOrange  // read in the draw lambdas, which are not composable
    // Remembered, so a recomposition keeps the cached line instead of
    // rebuilding it for a new instance.
    val ring = MaterialTheme.colorScheme.surfaceContainer
    val received = SuccessGreen
    val sent = MoneroOrange
    val receivedArrow = rememberVectorPainter(Icons.Filled.ArrowDownward)
    val sentArrow = rememberVectorPainter(Icons.Filled.ArrowUpward)
    val badge = remember(ring, received, sent, receivedArrow, sentArrow) {
        BadgeArt(ring, received, sent, receivedArrow, sentArrow)
    }

    val scrub = remember(points) { ScrubState() }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val haptic = LocalHapticFeedback.current

    fun update(index: Int) {
        if (scrub.selected == index) return
        scrub.selected = index
        currentOnSelect(index.takeIf { it >= 0 })
    }

    fun pin(marker: ChartMarker) {
        val index = points.indexOfFirst { it.timestamp == marker.timestamp }
        if (index < 0) {
            update(-1)
            return
        }
        scrub.pinned = marker
        update(index)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Up is the next marker in time, down the one before. From none, next
    // starts at the oldest and previous at the newest.
    fun step(forward: Boolean): Boolean {
        if (markers.isEmpty()) return false
        val current = scrub.pinned?.let { markers.indexOf(it) }?.takeIf { it >= 0 }
        val next = if (forward) {
            current?.let { min(it + 1, markers.lastIndex) } ?: 0
        } else {
            current?.let { max(it - 1, 0) } ?: markers.lastIndex
        }
        pin(markers[next])
        return true
    }

    // The summary, or the pinned marker and where it falls: "Received
    // 2.0000 XMR, Sep 20, 2026 at 3:05 PM, portfolio $1,234.56, 2 of 5".
    val pinned = scrub.pinned
    val pinnedIndex = pinned?.let { markers.indexOf(it) } ?: -1
    val stateText = if (pinned != null && pinnedIndex >= 0) {
        "${pinned.label}, ${pinned.valueText}, ${pinnedIndex + 1} of ${markers.size}"
    } else {
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        if (first != null && last != null) speech.summary(first.value, last.value) else ""
    }
    val actions = if (markers.isEmpty()) emptyList() else listOf(
        CustomAccessibilityAction("Next ${speech.markerName}") { step(forward = true) },
        CustomAccessibilityAction("Previous ${speech.markerName}") { step(forward = false) }
    )

    Box(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = speech.label
                stateDescription = stateText
                if (actions.isNotEmpty()) customActions = actions
            }
            .pointerInput(layout, scrub) {
                val tapSlop = TAP_SLOP.toPx()
                val verticalLock = VERTICAL_LOCK.toPx()
                val markerReach = MARKER_HIT_RADIUS.toPx()

                fun select(x: Float) {
                    update(layout.indexAt(x, layout.plot(size.width.toFloat(), size.height.toFloat())))
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pinnedAtTouchStart = scrub.pinned
                    scrub.pinned = null
                    select(down.position.x)
                    var scrolling = false
                    var horizontal = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            update(-1)
                            break
                        }
                        val delta = change.position - down.position
                        if (change.changedToUpIgnoreConsumed()) {
                            val tapped = !scrolling && abs(delta.x) < tapSlop && abs(delta.y) < tapSlop
                            val plot = layout.plot(size.width.toFloat(), size.height.toFloat())
                            val marker = if (tapped) layout.markerNear(change.position, plot, markerReach) else null
                            if (marker != null && marker != pinnedAtTouchStart) pin(marker) else update(-1)
                            break
                        }
                        if (scrolling) continue

                        val dx = abs(delta.x)
                        val dy = abs(delta.y)
                        if (!horizontal) {
                            if (dy > verticalLock && dy > dx * 1.5f) {
                                scrolling = true
                                update(-1)
                                continue
                            }
                            if (dx > viewConfiguration.touchSlop && dx >= dy) horizontal = true
                        }
                        // A scrub owns the drag, so the page does not scroll under it.
                        if (horizontal) change.consume()
                        select(change.position.x)

                        if (!horizontal) {
                            // The page sees the move after the chart does. If it took
                            // it, the page is scrolling.
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            if (final.changes.any { it.id == down.id && it.isConsumed }) {
                                scrolling = true
                                update(-1)
                            }
                        }
                    }
                }
            }
    ) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawWithCache {
                    val plot = layout.plot(size.width, size.height)
                    val line = Path()
                    val area = Path()
                    layout.points.forEachIndexed { i, point ->
                        val x = layout.xOf(point.timestamp, plot)
                        val y = layout.yOf(point.value, plot)
                        if (i == 0) {
                            line.moveTo(x, y)
                            area.moveTo(x, plot.bottom)
                            area.lineTo(x, y)
                        } else {
                            line.lineTo(x, y)
                            area.lineTo(x, y)
                        }
                    }
                    layout.points.lastOrNull()?.let { area.lineTo(layout.xOf(it.timestamp, plot), plot.bottom) }
                    area.close()
                    val fill = Brush.verticalGradient(
                        colors = listOf(orange.copy(alpha = 0.4f), orange.copy(alpha = 0f)),
                        startY = plot.top,
                        endY = plot.bottom
                    )
                    val stroke = Stroke(width = LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val gap = LABEL_GAP.toPx()

                    onDrawBehind {
                        layout.drawGrid(this, plot, gridColor)
                        drawPath(area, fill)
                        drawPath(line, orange, style = stroke)
                        layout.drawLabels(this, plot, gap)
                        for (marker in layout.markers) {
                            val center = Offset(layout.xOf(marker.timestamp, plot), layout.yOf(marker.value, plot))
                            drawBadge(center, marker.style, badge, scale = 1f)
                        }
                    }
                }
        )
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawBehind {
                    val index = scrub.selected
                    val point = layout.points.getOrNull(index) ?: return@drawBehind
                    val plot = layout.plot(size.width, size.height)
                    val x = layout.xOf(point.timestamp, plot)
                    val y = layout.yOf(point.value, plot)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx()))
                    drawLine(indicatorColor, Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx(), pathEffect = dash)

                    // On a marker, the marker itself shows the selection.
                    val marker = layout.markerAt(point.timestamp)
                    if (marker != null) {
                        drawCircle(badge.tint(marker.style).copy(alpha = 0.15f), SELECTED_HALO_RADIUS.toPx(), Offset(x, y))
                        drawBadge(Offset(x, y), marker.style, badge, scale = SELECTED_BADGE_SCALE)
                    } else {
                        drawCircle(orange, DOT_RADIUS.toPx(), Offset(x, y))
                    }
                }
        )
    }
}

/**
 * Chart dates in the user's locale, clock and time zone. A new set of
 * formatters is made only when one of them changes.
 */
@Composable
fun rememberChartDateFormats(): ChartDateFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(context)
    val timeZoneId = TimeZone.getDefault().id
    return remember(locale, use24Hour, timeZoneId) {
        ChartDateFormats(locale, TimeZone.getTimeZone(timeZoneId), use24Hour) { skeleton ->
            DateFormat.getBestDateTimePattern(locale, skeleton)
        }
    }
}

/** The selected sample and the pinned marker. New points get a fresh one. */
private class ScrubState {
    var selected by mutableIntStateOf(-1)
    var pinned by mutableStateOf<ChartMarker?>(null)
}

/** The colors and arrows of a marker badge. */
private class BadgeArt(
    val ring: Color,
    val received: Color,
    val sent: Color,
    val receivedArrow: Painter,
    val sentArrow: Painter
) {
    fun tint(style: ChartMarker.Style): Color = if (style == ChartMarker.Style.RECEIVED) received else sent
    fun arrow(style: ChartMarker.Style): Painter = if (style == ChartMarker.Style.RECEIVED) receivedArrow else sentArrow
}

/**
 * A disc in the activity row's color with its arrow, turned 45 degrees as
 * the rows turn it. A ring in the card's color cuts it out of the line.
 */
private fun DrawScope.drawBadge(center: Offset, style: ChartMarker.Style, art: BadgeArt, scale: Float) {
    drawCircle(art.ring, BADGE_RING_RADIUS.toPx() * scale, center)
    drawCircle(art.tint(style), BADGE_RADIUS.toPx() * scale, center)
    val side = BADGE_ARROW_SIZE.toPx() * scale
    rotate(45f, pivot = center) {
        translate(center.x - side / 2, center.y - side / 2) {
            with(art.arrow(style)) { draw(Size(side, side), colorFilter = ColorFilter.tint(Color.White)) }
        }
    }
}

/**
 * Everything about the chart that depends on its data and not on the
 * finger: tick values, measured labels and the time span. Positions come
 * from the plot rectangle, the size less the label gutters.
 */
private class ChartLayout(
    val points: List<ChartPoint>,
    val domain: ClosedFloatingPointRange<Double>,
    val markers: List<ChartMarker>,
    private val yTicks: List<Double>,
    private val yLabels: List<TextLayoutResult>,
    private val xTicks: List<Long>,
    private val xLabels: List<TextLayoutResult>,
    private val labelGap: Float,
    fixedLabelWidth: Float? = null
) {
    private val t0 = points.firstOrNull()?.timestamp ?: 0L
    private val t1 = points.lastOrNull()?.timestamp ?: 0L
    private val yLabelWidth = fixedLabelWidth ?: (yLabels.maxOfOrNull { it.size.width }?.toFloat() ?: 0f)
    private val xLabelHeight = xLabels.maxOfOrNull { it.size.height }?.toFloat() ?: 0f
    private val markerByTimestamp = markers.associateBy { it.timestamp }

    /** Labels sit right of the plot and below it; without axes the plot fills the chart. */
    fun plot(width: Float, height: Float): Rect {
        val right = if (yLabels.isEmpty()) width else width - yLabelWidth - labelGap
        val bottom = if (xLabels.isEmpty()) height else height - xLabelHeight - labelGap
        return Rect(0f, 0f, max(right, 0f), max(bottom, 0f))
    }

    /** A single sample sits in the middle. */
    fun xOf(timestamp: Long, plot: Rect): Float {
        if (t1 <= t0) return plot.center.x
        return plot.left + ((timestamp - t0).toDouble() / (t1 - t0) * plot.width).toFloat()
    }

    fun yOf(value: Double, plot: Rect): Float {
        val span = domain.endInclusive - domain.start
        if (span <= 0) return plot.center.y
        return plot.bottom - ((value - domain.start) / span * plot.height).toFloat()
    }

    /** The sample nearest in time to [x], clamped to the plot; -1 for no samples. */
    fun indexAt(x: Float, plot: Rect): Int {
        if (points.isEmpty()) return -1
        if (plot.width <= 0f || t1 <= t0) return points.lastIndex
        val clamped = x.coerceIn(plot.left, plot.right)
        val time = t0 + ((clamped - plot.left) / plot.width * (t1 - t0)).toDouble().roundToLong()
        return points.nearestIndexByTimestamp(time) { it.timestamp }
    }

    fun markerAt(timestamp: Long): ChartMarker? = markerByTimestamp[timestamp]

    /** The marker closest to [position], if one is within reach of a finger. */
    fun markerNear(position: Offset, plot: Rect, reach: Float): ChartMarker? =
        markers
            .map { it to hypot(xOf(it.timestamp, plot) - position.x, yOf(it.value, plot) - position.y) }
            .filter { it.second <= reach }
            .minByOrNull { it.second }
            ?.first

    fun drawGrid(scope: DrawScope, plot: Rect, color: Color) = with(scope) {
        for (value in yTicks) {
            val y = yOf(value, plot)
            drawLine(color, Offset(plot.left, y), Offset(plot.right, y), Stroke.HairlineWidth)
        }
        for (tick in xTicks) {
            val x = xOf(tick, plot)
            drawLine(color, Offset(x, plot.top), Offset(x, plot.bottom), Stroke.HairlineWidth)
        }
    }

    /**
     * Y labels right of the plot, centered on their lines. X labels start
     * at their line, as Swift Charts places them; one that would run into
     * the label before it is left out.
     */
    fun drawLabels(scope: DrawScope, plot: Rect, gap: Float) = with(scope) {
        for ((i, value) in yTicks.withIndex()) {
            val label = yLabels[i]
            val top = (yOf(value, plot) - label.size.height / 2f)
                .coerceIn(0f, max(size.height - label.size.height, 0f))
            drawText(label, topLeft = Offset(plot.right + gap, top))
        }
        var lastEnd = Float.NEGATIVE_INFINITY
        for ((i, tick) in xTicks.withIndex()) {
            val label = xLabels[i]
            val left = (xOf(tick, plot) + X_LABEL_INSET.toPx())
                .coerceIn(0f, max(size.width - label.size.width, 0f))
            if (left < lastEnd + gap) continue
            drawText(label, topLeft = Offset(left, plot.bottom + gap))
            lastEnd = left + label.size.width
        }
    }

    companion object {
        fun of(
            points: List<ChartPoint>,
            domain: ClosedFloatingPointRange<Double>,
            axes: ChartAxes?,
            markers: List<ChartMarker>,
            measurer: TextMeasurer,
            style: TextStyle,
            labelGap: Float,
            labelWidth: Float?
        ): ChartLayout {
            if (axes == null || points.isEmpty()) {
                return ChartLayout(points, domain, markers, emptyList(), emptyList(), emptyList(), emptyList(), labelGap)
            }
            val span = domain.endInclusive - domain.start
            // As many decimals as the span needs: none for a $400 span, two
            // for a portfolio that moved 80 cents.
            val digits = if (span < 5) 2 else 0
            val yTicks = niceTicks(domain.start, domain.endInclusive, count = 3)
            val yLabels = yTicks.map {
                val text = MoneyFormat.format(it, axes.currency, digits)
                var fitted = style
                if (labelWidth != null) {
                    while (fitted.fontSize.value * 0.95f >= style.fontSize.value * 0.75f &&
                        measurer.measure(text, fitted, maxLines = 1, softWrap = false).size.width > labelWidth
                    ) {
                        fitted = fitted.copy(fontSize = fitted.fontSize * 0.95f)
                    }
                }
                measurer.measure(
                    text, fitted, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = labelWidth?.toInt() ?: Constraints.Infinity)
                )
            }

            val formats = axes.formats
            val xTicks = axes.time.ticks(points.first().timestamp, points.last().timestamp, formats.timeZone, formats.locale)
            val xLabels = xTicks.map { measurer.measure(formats.tickLabel(axes.time, it), style) }
            return ChartLayout(points, domain, markers, yTicks, yLabels, xTicks, xLabels, labelGap, labelWidth)
        }

        /**
         * Round values from [start] to [stop], about [count] of them: steps of
         * 1, 2 or 5 times a power of ten (the d3 and Swift Charts rule).
         */
        fun niceTicks(start: Double, stop: Double, count: Int): List<Double> {
            if (!(stop > start) || count <= 0) return emptyList()
            val step = (stop - start) / count
            val power = floor(log10(step))
            val error = step / 10.0.pow(power)
            val factor = when {
                error >= sqrt(50.0) -> 10.0
                error >= sqrt(10.0) -> 5.0
                error >= sqrt(2.0) -> 2.0
                else -> 1.0
            }
            // Negative powers divide by an exact integer, so 0.1 steps stay exact.
            return if (power < 0) {
                val inverse = 10.0.pow(-power) / factor
                val first = ceil(start * inverse - 1e-9).toLong()
                val last = floor(stop * inverse + 1e-9).toLong()
                (first..last).map { it / inverse }
            } else {
                val increment = 10.0.pow(power) * factor
                val first = ceil(start / increment - 1e-9).toLong()
                val last = floor(stop / increment + 1e-9).toLong()
                (first..last).map { it * increment }
            }
        }
    }
}

private val LABEL_GAP = 4.dp
private val X_LABEL_INSET = 2.dp
private val LINE_WIDTH = 2.dp
private val DOT_RADIUS = 5.dp
private val BADGE_RADIUS = 9.dp
private val BADGE_RING_RADIUS = 11.dp
private val BADGE_ARROW_SIZE = 12.dp
private val SELECTED_HALO_RADIUS = 19.dp
private const val SELECTED_BADGE_SCALE = 1.25f

/** A lift within this of the touch is a tap. */
private val TAP_SLOP = 10.dp

/** A first move this far and mostly vertical is the page scrolling. */
private val VERTICAL_LOCK = 16.dp

/** Half of the 44 dp minimum hit target. */
private val MARKER_HIT_RADIUS = 22.dp
