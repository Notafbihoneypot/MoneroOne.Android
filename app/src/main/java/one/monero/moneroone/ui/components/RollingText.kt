package one.monero.moneroone.ui.components

import android.os.Build
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import one.monero.moneroone.ui.theme.TabularFigures
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Text whose changed characters roll when the value changes: the Compose
 * counterpart of iOS `.contentTransition(.numericText())` with
 * `.monospacedDigit()`, on the balance card and the Price tab.
 *
 * As on iOS, only the characters that changed move, always the same way
 * whether the value rises or falls: the old one drops, shrinks to about half
 * its size and fades out, the new one comes down from above and grows into
 * its place, and both blur while they move (Android 12 and later). Nothing
 * clips them, so no hard edge shows. The rolls ripple from left to right,
 * each place a little after the one before it.
 *
 * While a finger scrubs a chart the value changes on almost every frame. The
 * first changed place then keeps moving toward each new character instead of
 * starting over, and a place that changes again before its roll has begun
 * lands on its last character at once, so the trailing digits stay sharp.
 *
 * With [minScale] under 1 the text shrinks to fit its width, never below
 * that share of its size: the iOS `minimumScaleFactor`.
 */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    /** iOS rolls briskly (0.1 s) while a finger scrubs a chart, 0.2 s for live changes. */
    durationMillis: Int = Motion.DIGIT_MS,
    minScale: Float = 1f
) {
    val glyphStyle = style.copy(
        fontSize = if (fontSize.isSpecified) fontSize else style.fontSize,
        fontWeight = fontWeight ?: style.fontWeight,
        fontFeatureSettings = TabularFigures
    )
    if (minScale >= 1f) {
        RollingLine(text, glyphStyle, color, durationMillis, modifier)
        return
    }
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxWidth = constraints.maxWidth
        val scale = remember(text, glyphStyle, maxWidth, minScale) {
            val width = measurer.measure(text, glyphStyle, maxLines = 1, softWrap = false).size.width
            // Each character is laid out on its own, and each rounds up a pixel at most.
            val drawn = width + text.length
            if (maxWidth == Constraints.Infinity || drawn <= maxWidth) 1f else (maxWidth.toFloat() / drawn).coerceAtLeast(minScale)
        }
        val fitted = if (scale < 1f) glyphStyle.copy(fontSize = glyphStyle.fontSize * scale) else glyphStyle
        RollingLine(text, fitted, color, durationMillis, Modifier)
    }
}

@Composable
private fun RollingLine(text: String, style: TextStyle, color: Color, durationMillis: Int, modifier: Modifier) {
    val line = remember { RollLine() }
    val glyphs = line.show(text, durationMillis)
    LaunchedEffect(line) { line.run() }

    val em = with(LocalDensity.current) { (if (style.fontSize.isSpecified) style.fontSize else 16.sp).toPx() }
    val travel = TRAVEL_EM * em
    val blur = BLUR_EM * em

    Layout(
        content = {
            for (glyph in glyphs) {
                key(glyph.id) {
                    Text(
                        text = glyph.char.toString(),
                        style = style,
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .layoutId(glyph)
                            .graphicsLayer { line.pose(this, glyph, travel, blur) }
                    )
                }
            }
        },
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }
    ) { measurables, _ ->
        val loose = Constraints()
        val placeables = measurables.map { it.measure(loose) }
        val owners = measurables.map { it.layoutId as RollGlyph }
        val widths = HashMap<RollGlyph, Int>(owners.size)
        owners.forEachIndexed { i, glyph -> widths[glyph] = placeables[i].width }
        // The characters of the text, in order, set the width. A place that
        // goes away keeps its last x while its character drops out.
        var width = 0
        for (slot in line.slots) {
            val shown = slot.to ?: continue
            slot.x = width
            width += widths[shown] ?: 0
        }
        // Every character sits on one baseline.
        val baselines = placeables.map { it[FirstBaseline].takeIf { b -> b != AlignmentLine.Unspecified } ?: 0 }
        val ascent = baselines.maxOrNull() ?: 0
        val height = placeables.indices.maxOfOrNull { ascent - baselines[it] + placeables[it].height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, placeable -> placeable.place(owners[i].slot.x, ascent - baselines[i]) }
        }
    }
}

/**
 * The places of the text, one per character, and the frame clock that moves
 * them. It changes only in composition and on frames, both on the main thread.
 */
private class RollLine {
    val slots = ArrayList<RollSlot>()
    private var glyphs: List<RollGlyph> = emptyList()
    private var shown: String? = null
    private var nextId = 0L

    /** The time of the latest frame the clock ran on. */
    private var now = 0L

    /** True while the clock runs, so [now] is the time of the frame being composed. */
    private var running = false

    /** The animator duration scale: 0 when the system turns animations off. */
    private var scale = 1f

    /** Every character's layer reads it, so each frame and each new text redraws them. */
    private val redraw = mutableIntStateOf(0)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Rolls each place whose character differs from the one shown; returns the characters to draw. */
    fun show(text: String, durationMillis: Int): List<RollGlyph> {
        val old = shown
        if (old == text) return glyphs
        shown = text
        prune()
        var first = 0
        if (old != null) {
            while (first < old.length && first < text.length && old[first] == text[first]) first++
        }
        val rolls = old != null && scale > 0f
        val duration = durationMillis.toFloat()
        for (i in first until max(slots.size, text.length)) {
            val slot = slots.getOrNull(i) ?: RollSlot().also { slots += it }
            val want = text.getOrNull(i)
            if (slot.to?.char == want) continue
            if (!rolls) {
                slot.rest(want?.let { RollGlyph(nextId++, it, slot) })
                continue
            }
            val delay = (i - first) * STAGGER * duration
            val moving = slot.moving(now)
            if (moving && i == first && want != null && slot.to != null) {
                // The first changed place turns toward the new character and keeps going.
                slot.retarget(RollGlyph(nextId++, want, slot), now, duration * scale)
            } else {
                // A roll that has not begun, or a later place, lands at once and rolls on from there.
                if (moving || slot.pending(now)) slot.land()
                slot.roll(want?.let { RollGlyph(nextId++, it, slot) }, delay, duration, now, if (running) scale else null)
            }
        }
        while (slots.isNotEmpty() && slots.last().let { it.to == null && it.from == null }) slots.removeAt(slots.lastIndex)
        glyphs = slots.flatMap { listOfNotNull(it.from, it.to) }
        redraw.intValue++
        if (rolls) wake.trySend(Unit)
        return glyphs
    }

    /** Drops the characters that have finished rolling out. */
    private fun prune() {
        for (slot in slots) {
            if (slot.from != null && slot.goneOut(now)) slot.from = null
        }
    }

    /** Runs the clock while anything moves, and sleeps between changes. */
    suspend fun run() {
        while (true) {
            scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            wake.receive()
            do {
                val frameScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                val moving = withFrameMillis { time ->
                    now = time
                    scale = frameScale
                    for (slot in slots) slot.start(time, frameScale)
                    val moving = slots.any { !it.settled(time) }
                    running = moving
                    redraw.intValue++
                    moving
                }
            } while (moving)
        }
    }

    /** Sets one character's layer for this frame. */
    fun pose(layer: GraphicsLayerScope, glyph: RollGlyph, travel: Float, blur: Float) {
        redraw.intValue
        val slot = glyph.slot
        val entering = glyph === slot.to
        // 0 in its place, 1 fully out of it.
        val away = when {
            entering -> 1f - slot.comeIn(now)
            glyph === slot.from -> slot.goOut(now)
            else -> 1f
        }
        layer.translationY = (if (entering) -away else away) * travel
        val size = 1f - (1f - MIN_SCALE) * away
        layer.scaleX = size
        layer.scaleY = size
        layer.alpha = 1f - away
        // The blur comes on fast and goes late, so a character is soft for most of its roll.
        val radius = (1f - (1f - away) * (1f - away)) * blur
        layer.renderEffect = if (radius >= 0.5f && away < 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BlurEffect(radius, radius, TileMode.Decal)
        } else {
            null
        }
    }
}

/**
 * One place in the text: the character it shows or rolls to, and the one
 * rolling out of it. As measured on iOS, the old character clears its place
 * quickly and the new one settles slowly.
 */
private class RollSlot {
    /** Null while the place goes away because the text got shorter. */
    var to: RollGlyph? = null
    var from: RollGlyph? = null

    /** Its x in the line, kept while its place goes away. */
    var x = 0

    /** How far [to] had come in when its current run began: 0 for a new roll. */
    private var startProgress = 1f
    private var startAt = 0L
    private var outAt = 0L

    /** The roll's duration in milliseconds, with the animator duration scale applied. */
    private var duration = 0f

    /** True until the clock gives the roll its start time; then [delay] and [baseDuration] get scaled. */
    private var waiting = false
    private var delay = 0f
    private var baseDuration = 0f

    /** 0 above its place, 1 in it. */
    fun comeIn(now: Long): Float = when {
        waiting || now < startAt -> startProgress
        duration <= 0f -> 1f
        else -> startProgress + (1f - startProgress) * LinearOutSlowInEasing.transform(((now - startAt) / (IN * duration)).coerceIn(0f, 1f))
    }

    /** 0 in its place, 1 gone below it. */
    fun goOut(now: Long): Float = when {
        waiting || now < outAt -> 0f
        duration <= 0f -> 1f
        else -> Motion.EaseInOut.transform(((now - outAt) / (OUT * duration)).coerceIn(0f, 1f))
    }

    /** A roll waits for its turn in the left-to-right ripple. */
    fun pending(now: Long): Boolean = waiting || now < startAt
    fun goneOut(now: Long): Boolean = !pending(now) && goOut(now) >= 1f
    fun settled(now: Long): Boolean = !pending(now) && (to == null || comeIn(now) >= 1f) && (from == null || goOut(now) >= 1f)
    fun moving(now: Long): Boolean = !pending(now) && !settled(now)

    /** Shows [glyph] in its place at once. */
    fun rest(glyph: RollGlyph?) {
        to = glyph
        land()
    }

    /** Ends the current roll with its new character in place. */
    fun land() {
        from = null
        startProgress = 1f
        startAt = 0L
        outAt = 0L
        duration = 0f
        waiting = false
    }

    /**
     * Rolls the shown character out and [next] in, after [delay] ms. [scale] is
     * the animator duration scale, or null while the clock sleeps: the roll then
     * starts on the clock's next frame.
     */
    fun roll(next: RollGlyph?, delay: Float, duration: Float, now: Long, scale: Float?) {
        from = to
        to = next
        startProgress = 0f
        if (scale != null) {
            waiting = false
            startAt = now + (delay * scale).toLong()
            outAt = startAt
            this.duration = duration * scale
        } else {
            waiting = true
            this.delay = delay
            baseDuration = duration
        }
    }

    /**
     * Swaps the incoming character mid-roll and runs the rest of the roll again
     * from here. While a finger scrubs, this happens on every frame, so the
     * place hovers part of the way in, soft, as on iOS, and settles when the
     * finger rests.
     */
    fun retarget(next: RollGlyph, now: Long, duration: Float) {
        startProgress = minOf(comeIn(now), HOVER)
        startAt = now
        this.duration = duration
        to = next
    }

    fun start(time: Long, scale: Float) {
        if (waiting) {
            waiting = false
            startAt = time + (delay * scale).toLong()
            outAt = startAt
            duration = baseDuration * scale
        }
    }
}

private class RollGlyph(val id: Long, val char: Char, val slot: RollSlot)

/** How far a character travels as it rolls, in ems: about half a digit's height. */
private const val TRAVEL_EM = 0.4f

/** The blur radius at the ends of a roll, in ems. */
private const val BLUR_EM = 0.12f

/** A character's size at the ends of its roll, as a share of its size in place. */
private const val MIN_SCALE = 0.5f

/** How long each place waits after the one to its left, as a share of the roll's duration. */
private const val STAGGER = 0.35f

/** How long the old character takes to clear its place, as a share of the roll's duration. */
private const val OUT = 1.2f

/** How long the new character takes to settle, as a share of the roll's duration. */
private const val IN = 3.5f

/** How far into its place a character that keeps changing gets before the next change. */
private const val HOVER = 0.7f
