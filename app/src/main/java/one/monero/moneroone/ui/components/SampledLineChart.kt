package one.monero.moneroone.ui.components

import one.monero.moneroone.core.locale.tr
import android.text.format.DateFormat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import kotlin.math.roundToInt
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
 * readout of the sample under the finger. The line, labels and paths are
 * rebuilt only when [points], [domain] or [axes] change; a scrub redraws
 * the overlay layer only, which keeps a few hundred samples smooth without
 * dropping any. Matches iOS SampledLineChart.
 *
 * A still finger starts the readout after [HOLD_DELAY_MS], a sideways drag
 * starts it at once, and a drag that starts up or down belongs to the page,
 * which scrolls. A tap on a marker pins its sample.
 *
 * Transient mode (price) drops the readout on lift unless a tap pinned a
 * marker; tapping the pinned marker again clears it. Callers key their own
 * selection on the same [points] instance: new points clear it here without
 * a call to [onSelect].
 *
 * Persistent mode (wallet history, [persistsSelection]) keeps the selection
 * after the finger lifts, until the parent sets [selectedTimestamp] back to
 * null (Now). The last sample is Now: selecting it reports null. After the
 * selected sample the line, the area and the markers fade instead of being
 * covered, so every marker stays whole.
 *
 * TalkBack sees one node: the label names the chart and the state sums up
 * the line. With markers the custom actions step through them, pinning each
 * as a tap would. In persistent mode the node is adjustable instead, as the
 * iOS chart is: a swipe up or down steps through the samples.
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
    axisLabelWidth: Dp? = null,
    /**
     * Keeps the line far enough inside the plot's edges that a selected
     * marker on the first or last sample, or at the top or bottom, is
     * whole. Set it in every range of a chart that can show markers, so the
     * plot does not shift when a range has none.
     */
    insetsForMarkers: Boolean = false,
    persistsSelection: Boolean = false,
    /** The parent's selected sample, by time; null is Now. Read in persistent mode only. */
    selectedTimestamp: Long? = null,
    /** False ignores touches, as a chart that is still loading does. */
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val dates = rememberChartDateFormats()
    // Axis labels: caption2 (tokens.json type.roles).
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val labelWidth = with(density) { axisLabelWidth?.toPx()?.times(fontScale) }
    val inset = with(density) { if (insetsForMarkers) MARKER_PLOT_INSET.toPx() else 0f }
    val layout = remember(points, domain, axes, markers, labelStyle, textMeasurer, density, labelWidth, inset) {
        ChartLayout.of(points, domain, axes, markers, textMeasurer, labelStyle, with(density) { LABEL_GAP.toPx() }, labelWidth, inset)
    }

    val gridColor = MoneroTheme.colors.separator
    val indicatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val orange = MoneroOrange  // read in the draw lambdas, which are not composable
    // Remembered, so a recomposition keeps the cached line instead of
    // rebuilding it for a new instance.
    val received = SuccessGreen
    val sent = MoneroOrange
    val receivedArrow = rememberVectorPainter(Icons.Filled.ArrowDownward)
    val sentArrow = rememberVectorPainter(Icons.Filled.ArrowUpward)
    val badge = remember(received, sent, receivedArrow, sentArrow) {
        BadgeArt(received, sent, receivedArrow, sentArrow)
    }

    // Transient selections belong to their points; a persistent one follows the parent.
    val scrub = remember(if (persistsSelection) null else points) { ScrubState() }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val parentTimestamp by rememberUpdatedState(selectedTimestamp)
    val haptic = LocalHapticFeedback.current

    fun parentIndex(): Int =
        parentTimestamp?.let { time -> layout.points.indexOfFirst { it.timestamp == time } } ?: -1

    /** What the chart shows: while a finger leads it is the finger's, else the parent's in persistent mode. */
    fun shownIndex(): Int = if (persistsSelection && !scrub.touching) parentIndex() else scrub.selected

    fun update(index: Int) {
        // The last sample is the live wallet, whose balance can include
        // transfers newer than the last price fetch. It is Now, not a past cutoff.
        val next = if (persistsSelection && index == layout.points.lastIndex) -1 else index
        if (next == shownIndex()) return
        scrub.selected = next
        if (next < 0) scrub.pinned = null
        currentOnSelect(next.takeIf { it >= 0 })
    }

    fun pin(marker: ChartMarker) {
        val index = layout.points.indexOfFirst { it.timestamp == marker.timestamp }
        if (index < 0) {
            update(-1)
            return
        }
        update(index)
        scrub.pinned = if (shownIndex() < 0) null else marker
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

    val shown = shownIndex()
    val pinnedIndex = if (persistsSelection) -1 else scrub.pinned?.let { markers.indexOf(it) } ?: -1
    val stateText = when {
        persistsSelection && shown >= 0 -> layout.points[shown].let { point ->
            // "Sep 20, 2026, 3:05 PM, $1,234.56, 12 of 168"
            tr("%s, %s, %s of %s", dates.abbreviatedDateTime(point.timestamp), speech.format(point.value), shown + 1, points.size)
        }
        pinnedIndex >= 0 -> {
            // "Received 2.0000 XMR, Sep 20, 2026 at 3:05 PM, portfolio $1,234.56, 2 of 5".
            val pinned = markers[pinnedIndex]
            tr("%s, %s, %s of %s", pinned.label, pinned.valueText, pinnedIndex + 1, markers.size)
        }
        else -> {
            val first = points.firstOrNull()
            val last = points.lastOrNull()
            if (first != null && last != null) speech.summary(first.value, last.value) else ""
        }
    }
    val actions = if (markers.isEmpty() || persistsSelection) emptyList() else listOf(
        CustomAccessibilityAction(tr("Next transaction")) { step(forward = true) },
        CustomAccessibilityAction(tr("Previous transaction")) { step(forward = false) }
    )
    val lastIndex = points.lastIndex
    // Fading after a cutoff and parting markers from the line erase the
    // chart's own pixels, so the chart draws in a layer of its own and the
    // card behind it is never cut.
    val composited = persistsSelection || markers.isNotEmpty()

    Box(
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = speech.label
                stateDescription = stateText
                if (persistsSelection && lastIndex > 0) {
                    val current = if (shown >= 0) shown else lastIndex
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = current.toFloat(),
                        range = 0f..lastIndex.toFloat(),
                        steps = max(lastIndex - 1, 0)
                    )
                    setProgress { target ->
                        update(target.roundToInt().coerceIn(0, lastIndex))
                        val index = shownIndex()
                        scrub.pinned = if (index >= 0) layout.markerAt(layout.points[index].timestamp) else null
                        true
                    }
                } else if (actions.isNotEmpty()) {
                    customActions = actions
                }
            }
            .pointerInput(layout, scrub, persistsSelection, enabled) {
                if (!enabled) return@pointerInput
                val holdSlop = HOLD_SLOP.toPx()
                val tapSlop = TAP_SLOP.toPx()
                val markerReach = MARKER_HIT_RADIUS.toPx()

                fun plot() = layout.plot(size.width.toFloat(), size.height.toFloat())
                fun select(x: Float) = update(layout.indexAt(x, plot()))

                /**
                 * A lifted finger. Persistent mode keeps what it chose; transient
                 * mode drops the readout unless a tap landed on a marker, and
                 * tapping the pinned marker again clears it.
                 */
                fun finish(position: Offset, tapped: Boolean, pinnedAtStart: ChartMarker?) {
                    val marker = if (tapped) layout.markerNear(position, plot(), markerReach) else null
                    if (persistsSelection) {
                        if (marker != null) pin(marker) else select(position.x)
                    } else if (marker != null && marker != pinnedAtStart) {
                        pin(marker)
                    } else {
                        update(-1)
                    }
                    scrub.touching = false
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    when (val start = awaitReadoutStart(down, holdSlop)) {
                        is ReadoutStart.Tap -> finish(start.position, tapped = true, pinnedAtStart = scrub.pinned)
                        is ReadoutStart.Begin -> {
                            // A held or sliding finger replaces a pinned marker.
                            val pinnedAtStart = scrub.pinned
                            if (persistsSelection) scrub.selected = parentIndex()
                            scrub.touching = true
                            scrub.pinned = null
                            select(start.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    // Cancelled: transient mode drops the readout, persistent keeps it.
                                    if (!persistsSelection) update(-1)
                                    scrub.touching = false
                                    break
                                }
                                if (change.changedToUpIgnoreConsumed()) {
                                    val travel = change.position - down.position
                                    finish(change.position, abs(travel.x) < tapSlop && abs(travel.y) < tapSlop, pinnedAtStart)
                                    break
                                }
                                // The readout owns the drag, so the page does not scroll under it.
                                change.consume()
                                select(change.position.x)
                            }
                        }
                        // The page is scrolling, or someone else took the touch.
                        ReadoutStart.None -> Unit
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { if (composited) compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer()
                    .drawWithCache {
                        val plot = layout.plot(size.width, size.height)
                        val line = Path()
                        val area = Path()
                        val floor = layout.yOf(domain.start, plot)
                        layout.points.forEachIndexed { i, point ->
                            val x = layout.xOf(point.timestamp, plot)
                            val y = layout.yOf(point.value, plot)
                            if (i == 0) {
                                line.moveTo(x, y)
                                area.moveTo(x, floor)
                                area.lineTo(x, y)
                            } else {
                                line.lineTo(x, y)
                                area.lineTo(x, y)
                            }
                        }
                        layout.points.lastOrNull()?.let { area.lineTo(layout.xOf(it.timestamp, plot), floor) }
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
                        }
                    }
            )
            // Bottom to top: the fade after a past cutoff, the cutoff's rule,
            // the markers, and the selection.
            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer()
                    .drawBehind {
                        val plot = layout.plot(size.width, size.height)
                        val index = if (persistsSelection && !scrub.touching) {
                            parentTimestamp?.let { time -> layout.points.indexOfFirst { it.timestamp == time } } ?: -1
                        } else {
                            scrub.selected
                        }
                        val point = layout.points.getOrNull(index)
                        val x = point?.let { layout.xOf(it.timestamp, plot) }
                        val cutoff = if (persistsSelection) point?.timestamp else null

                        if (point != null && x != null) {
                            if (persistsSelection) {
                                // Erases part of the line and the area in this layer,
                                // so the card shows through and no color lies over the plot.
                                drawRect(
                                    color = Color.Black.copy(alpha = 1f - FADED_OPACITY),
                                    topLeft = Offset(x, plot.top),
                                    size = Size(max(plot.right - x, 0f), plot.height),
                                    blendMode = BlendMode.DstOut
                                )
                            }
                            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx()))
                            drawLine(indicatorColor, Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx(), pathEffect = dash)
                        }

                        for (marker in layout.markers) {
                            val center = Offset(layout.xOf(marker.timestamp, plot), layout.yOf(marker.value, plot))
                            drawBadge(center, marker.style, badge, scale = 1f, faded = cutoff != null && marker.timestamp > cutoff)
                        }

                        if (point != null && x != null) {
                            val center = Offset(x, layout.yOf(point.value, plot))
                            // On a marker, the marker itself shows the selection.
                            val marker = layout.markerAt(point.timestamp)
                            if (marker != null) {
                                drawCircle(badge.tint(marker.style).copy(alpha = 0.15f), SELECTED_HALO_RADIUS.toPx(), center)
                                drawBadge(center, marker.style, badge, scale = SELECTED_BADGE_SCALE, faded = false)
                            } else {
                                drawCircle(orange, DOT_RADIUS.toPx(), center)
                            }
                        }
                    }
            )
        }
    }
}

/** How a touch on a chart turned out before its readout starts. */
private sealed interface ReadoutStart {
    /** A still finger held for [HOLD_DELAY_MS], or a sideways drag. */
    class Begin(val position: Offset) : ReadoutStart
    /** Lifted before either. */
    class Tap(val position: Offset) : ReadoutStart
    /** A drag up or down, which is the page's, or a touch taken elsewhere. */
    data object None : ReadoutStart
}

/**
 * Waits until a touch is a hold, a sideways drag, a tap or the page's
 * scroll (iOS ChartTouch). A sideways drag is consumed at once, before the
 * page's scroll sees it; a drag that starts up or down is left alone.
 */
private suspend fun AwaitPointerEventScope.awaitReadoutStart(down: PointerInputChange, holdSlop: Float): ReadoutStart {
    var position = down.position
    var holding = true

    // An extension, so it runs in the restricted pointer scope of whoever calls it.
    suspend fun AwaitPointerEventScope.next(): ReadoutStart? {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return ReadoutStart.None
        if (change.changedToUpIgnoreConsumed()) return ReadoutStart.Tap(change.position)
        if (change.isConsumed) return ReadoutStart.None
        position = change.position
        val delta = position - down.position
        if (delta.getDistance() > holdSlop) holding = false
        if (delta.getDistance() <= viewConfiguration.touchSlop) return null
        if (abs(delta.y) <= abs(delta.x) * SIDEWAYS_RATIO) {
            change.consume()
            return ReadoutStart.Begin(position)
        }
        return ReadoutStart.None
    }

    val early = withTimeoutOrNull(HOLD_DELAY_MS) {
        var result: ReadoutStart? = null
        while (result == null) result = next()
        result
    }
    if (early != null) return early
    if (holding) return ReadoutStart.Begin(position)
    // The finger drifted, too little to tell a slide from a scroll yet.
    var result: ReadoutStart? = null
    while (result == null) result = next()
    return result
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

/** The selected sample, the pinned marker, and whether a finger leads. */
private class ScrubState {
    var selected by mutableIntStateOf(-1)
    var pinned by mutableStateOf<ChartMarker?>(null)
    var touching by mutableStateOf(false)
}

/** The colors and arrows of a marker badge. */
private class BadgeArt(
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
 * the rows turn it. A ring around it erases the chart under it, so the line
 * parts around the disc in the card's own color, glass or not. Draw it in
 * the chart's own layer, or the ring cuts through the card as well. After
 * a past cutoff the disc and its arrow fade as one shape.
 */
private fun DrawScope.drawBadge(center: Offset, style: ChartMarker.Style, art: BadgeArt, scale: Float, faded: Boolean) {
    drawCircle(Color.Black, BADGE_RING_RADIUS.toPx() * scale, center, blendMode = BlendMode.DstOut)
    val radius = BADGE_RADIUS.toPx() * scale
    if (faded) {
        drawIntoCanvas {
            it.saveLayer(Rect(center, radius), Paint().apply { alpha = FADED_OPACITY })
        }
    }
    drawCircle(art.tint(style), radius, center)
    val side = BADGE_ARROW_SIZE.toPx() * scale
    rotate(45f, pivot = center) {
        translate(center.x - side / 2, center.y - side / 2) {
            with(art.arrow(style)) { draw(Size(side, side), colorFilter = ColorFilter.tint(Color.White)) }
        }
    }
    if (faded) drawIntoCanvas { it.restore() }
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
    fixedLabelWidth: Float? = null,
    /** Space between the plot's edges and the line (iOS plotDimension padding). */
    private val inset: Float = 0f
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

    /** Where the line runs: the plot less the inset. */
    private fun line(plot: Rect): Rect = Rect(
        plot.left + inset, plot.top + inset,
        max(plot.right - inset, plot.left + inset), max(plot.bottom - inset, plot.top + inset)
    )

    /** A single sample sits in the middle. */
    fun xOf(timestamp: Long, plot: Rect): Float {
        if (t1 <= t0) return plot.center.x
        val line = line(plot)
        return line.left + ((timestamp - t0).toDouble() / (t1 - t0) * line.width).toFloat()
    }

    fun yOf(value: Double, plot: Rect): Float {
        val span = domain.endInclusive - domain.start
        if (span <= 0) return plot.center.y
        val line = line(plot)
        return line.bottom - ((value - domain.start) / span * line.height).toFloat()
    }

    /** The sample nearest in time to [x], clamped to the line; -1 for no samples. */
    fun indexAt(x: Float, plot: Rect): Int {
        if (points.isEmpty()) return -1
        val line = line(plot)
        if (line.width <= 0f || t1 <= t0) return points.lastIndex
        val clamped = x.coerceIn(line.left, line.right)
        val time = t0 + ((clamped - line.left) / line.width * (t1 - t0)).toDouble().roundToLong()
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
            labelWidth: Float?,
            inset: Float = 0f
        ): ChartLayout {
            if (axes == null || points.isEmpty()) {
                return ChartLayout(points, domain, markers, emptyList(), emptyList(), emptyList(), emptyList(), labelGap, inset = inset)
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
            return ChartLayout(points, domain, markers, yTicks, yLabels, xTicks, xLabels, labelGap, labelWidth, inset)
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

/** What the line, the area and a marker keep after a past cutoff. */
private const val FADED_OPACITY = 0.35f

/**
 * How far inside the plot's edges the line stays with [SampledLineChart]'s
 * insetsForMarkers, so a selected badge on the first or last sample, or at
 * the top, is whole: half a selected badge and its ring, rounded up (iOS 14 pt).
 */
private val MARKER_PLOT_INSET = ceil(BADGE_RING_RADIUS.value * SELECTED_BADGE_SCALE).dp

/** How long a still finger rests before the readout starts (iOS ChartTouch.holdDelay). */
private const val HOLD_DELAY_MS = 250L

/** How far a resting finger may drift and still start the readout. */
private val HOLD_SLOP = 8.dp

/** A drag no steeper than this (dy over dx) reads out the chart; a steeper one scrolls the page. */
private const val SIDEWAYS_RATIO = 1.5f

/** A lift within this of the touch is a tap. */
private val TAP_SLOP = 10.dp

/** Half of the 44 dp minimum hit target. */
private val MARKER_HIT_RADIUS = 22.dp
