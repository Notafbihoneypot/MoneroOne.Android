package one.monero.moneroone.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.monero.moneroone.core.locale.tr
import one.monero.moneroone.ui.theme.MoneroTheme
import one.monero.moneroone.ui.theme.TabularFigures
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What focus mode shows: the code, what it points at (TalkBack reads it with
 * the code; the screen does not show it) and the requested amount.
 */
data class QrFocusItem(val content: String, val title: String, val amount: BigDecimal? = null)

enum class QrRecedeEdge { Top, Bottom }

/**
 * A screen's focus mode (iOS QRFocus): what its views read to step back, and
 * what the code they tap opens. One progress drives the grow, the fade of the
 * page behind and the parts that step back, so all of them move on one curve.
 */
@Stable
class QrFocusState internal constructor(private val scope: CoroutineScope, internal val grows: Boolean) {
    /** 0 with the code on the screen, 1 in focus mode. */
    internal val progress = Animatable(0f)

    /** True while the large copy is on screen: the code on the screen hides and keeps its place. */
    var hidesSource by mutableStateOf(false)
        private set
    internal var presented by mutableStateOf<QrFocusItem?>(null)
        private set
    /** True from the tap until the close starts: the brightness follows it. */
    internal var expanded by mutableStateOf(false)
        private set

    /** Where the code on the screen sits, in window pixels: the copy grows from it and shrinks back to it. */
    internal var sourceBounds by mutableStateOf(Rect.Zero)
    internal var containerOrigin by mutableStateOf(Offset.Zero)

    private var move: Job? = null

    /** The grow, or a cross-fade when animations are off (iOS Reduce Motion). */
    private val spec get() = if (grows) tween<Float>(MOVE_MILLIS, easing = MoveEasing) else tween(FADE_MILLIS, easing = FadeEasing)

    fun open(item: QrFocusItem) {
        if (presented != null) return
        // The copy starts on top of the code and the code hides in the same frame.
        presented = item
        hidesSource = grows
        expanded = true
        move = scope.launch { progress.animateTo(1f, spec) }
    }

    fun close() {
        if (presented == null || !expanded) return
        expanded = false
        move?.cancel()
        move = scope.launch {
            progress.animateTo(0f, spec)
            // The copy is back on top of the code: show the code and drop the
            // copy in one frame, so nothing blinks.
            hidesSource = false
            presented = null
        }
    }

    internal companion object {
        /**
         * iOS QRFocus.move: timingCurve(0.25, 0.1, 0.25, 1) over 0.42 s, both
         * ways. It stays within a percent of a spring but ends on time, with no
         * last-pixel snap.
         */
        const val MOVE_MILLIS = 420
        val MoveEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
        const val FADE_MILLIS = 250
        val FadeEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    }
}

/**
 * A screen whose QR code grows in place into focus mode (iOS
 * QRFocusContainer): the code grows from where it sits to the width of the
 * screen, the Monero One lockup slides in above it, the rest of the screen
 * steps back behind the page color, and the screen goes to full brightness.
 * A tap anywhere, a swipe down or Back shrinks it back along the same path.
 * The overlay covers the top bar without moving it, and nothing behind it
 * takes a touch.
 */
@Composable
fun QrFocusContainer(content: @Composable (QrFocusState) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = remember { QrFocusState(scope, grows = !animationsOff(context)) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { focus.containerOrigin = it.positionInWindow() }) {
        val presented = focus.presented
        // One code for TalkBack at a time: the screen is hidden while the copy is up.
        Box(if (presented != null) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
            content(focus)
        }
        if (presented != null) QrFocusOverlay(presented, focus)
    }
}

/** Steps out of the way while focus mode is open: fades, and moves toward [toward] unless animations are off. */
fun Modifier.qrFocusRecede(focus: QrFocusState, toward: QrRecedeEdge): Modifier = graphicsLayer {
    val p = focus.progress.value
    alpha = 1f - p
    translationY = if (focus.grows) p * (if (toward == QrRecedeEdge.Top) -24.dp.toPx() else 32.dp.toPx()) else 0f
    // Per draw, not through a layer: a layer the size of the view would cut off its shadows mid-fade.
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/**
 * The code where it sits on a screen, and the tap that opens focus mode (iOS
 * FocusableQRPlate). One TalkBack element, a button; while focus mode is open
 * the code hides and keeps its place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusableQrPlate(
    item: QrFocusItem,
    side: Dp,
    focus: QrFocusState,
    modifier: Modifier = Modifier,
    label: String = tr("QR code for Monero address"),
    onLongClick: (() -> Unit)? = null,
    actions: List<CustomAccessibilityAction> = emptyList()
) {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val open = { openFocus(view, keyboard, focusManager, focus, item) }
    QrPlate(
        content = item.content,
        side = side,
        modifier = modifier
            .onGloballyPositioned { focus.sourceBounds = Rect(it.positionInWindow(), it.size.toSize()) }
            .graphicsLayer { alpha = if (focus.hidesSource) 0f else 1f }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { open(); true }
                if (onLongClick != null) onLongClick { onLongClick(); true }
                if (actions.isNotEmpty()) customActions = actions
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = open
            )
    )
}

private fun openFocus(
    view: View, keyboard: SoftwareKeyboardController?, focusManager: FocusManager,
    focus: QrFocusState, item: QrFocusItem
) {
    if (focus.presented != null) return
    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    // End editing (the Receive amount), so the keyboard does not stay up under the code.
    focusManager.clearFocus()
    keyboard?.hide()
    focus.open(item)
}

/**
 * Focus mode itself (iOS QRFocusView): the Monero One lockup, the code as
 * large as the screen allows (16 dp margins, 448 dp at most) and the
 * requested amount, over the page color. Like iOS it shows no address: the
 * code is the address.
 */
@Composable
private fun QrFocusOverlay(item: QrFocusItem, focus: QrFocusState) {
    val grows = focus.grows
    val code = rememberQrBitmap(item.content)
    val logo = QrCodes.cachedLogo
    val border = !MoneroTheme.isDark
    val page = MaterialTheme.colorScheme.background
    val amount = item.amount?.let { "${formatXmr(it)} XMR" }
    val value = listOfNotNull(item.title, amount).joinToString(", ")

    BackHandler { focus.close() }
    FullBrightness(isOn = focus.expanded)
    HighFrameRate()

    val density = LocalDensity.current
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = density.density
        val insetLeft = safe.getLeft(density, LayoutDirection.Ltr).toFloat()
        val insetTop = safe.getTop(density).toFloat()
        val safeWidth = constraints.maxWidth - insetLeft - safe.getRight(density, LayoutDirection.Ltr)
        val safeHeight = constraints.maxHeight - insetTop - safe.getBottom(density)
        val layout = FocusLayout(safeWidth / d, safeHeight / d, item.amount != null)
        val centerX = insetLeft + safeWidth / 2f
        // The lockup and the amount center on the safe area, not the screen.
        val shiftX = (centerX - constraints.maxWidth / 2f).roundToInt()
        val sidePx = layout.side * d
        val target = Rect(Offset(centerX - sidePx / 2f, insetTop + layout.plateCenterY * d - sidePx / 2f),
            androidx.compose.ui.geometry.Size(sidePx, sidePx))

        // The page color and the taps that close it: a tap anywhere, or a swipe down.
        Spacer(
            Modifier
                .fillMaxSize()
                .pointerInput(focus) {
                    val tapSlop = 10.dp.toPx()
                    val swipe = 60.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var last = down.position
                        var lifted = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position
                            change.consume()
                            if (!change.pressed) { lifted = true; break }
                        }
                        val moved = last - down.position
                        if (lifted && (moved.getDistance() < tapSlop || moved.y > swipe)) focus.close()
                    }
                }
                .drawBehind { drawRect(page, alpha = focus.progress.value) }
        )

        // The lockup slides down from the top as the code grows, on the grow's
        // own curve. It sits under the code, as on iOS: the code passes over
        // it on the way, and it never shows through the code.
        Row(
            Modifier
                .offset { IntOffset(shiftX, (insetTop + (layout.lockupCenterY - LOCKUP_HEIGHT / 2f) * d).roundToInt()) }
                .widthIn(max = (safeWidth / d).dp)
                .height(LOCKUP_HEIGHT.dp)
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    val p = focus.progress.value
                    alpha = p
                    translationY = if (grows) -20.dp.toPx() * (1f - p) else 0f
                }
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (logo != null) Image(logo.asImageBitmap(), null, Modifier.size(LOCKUP_HEIGHT.dp))
            Text("Monero One", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }

        // The code, over the lockup. It takes no touches, so a tap still
        // reaches the page color under it.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val p = focus.progress.value
                    val from = focus.sourceBounds.translate(-focus.containerOrigin)
                    // Large when open; on top of the code on the screen before it
                    // grows and after it shrinks back. With animations off it stays
                    // large and fades.
                    val rect = if (grows && from.width > 0f) lerpRect(from, target, p) else target
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val faded = !grows && p < 1f
                        if (faded) native.saveLayerAlpha(null, (p * 255).roundToInt())
                        // The copy's shadow goes away in the frame that shows the code
                        // on the screen again, so two shadows never stack.
                        QrCodes.drawPlate(native, code, logo, RectF(rect.left, rect.top, rect.right, rect.bottom),
                            d, castsShadow = focus.hidesSource || !grows, border = border)
                        if (faded) native.restore()
                    }
                }
        )

        // TalkBack's code, where the large code ends up.
        Box(
            Modifier
                .offset { IntOffset(target.left.roundToInt(), target.top.roundToInt()) }
                .size(layout.side.dp)
                .testTag("qr-focus")
                .semantics {
                    contentDescription = tr("QR code for Monero address")
                    stateDescription = value
                    role = Role.Image
                    onClick(label = tr("Close")) { focus.close(); true }
                }
        )

        if (amount != null) Box(
            Modifier
                .offset { IntOffset(shiftX, (insetTop + (layout.amountCenterY - AMOUNT_HEIGHT / 2f) * d).roundToInt()) }
                .align(Alignment.TopCenter)
                .widthIn(max = (safeWidth / d).dp - 32.dp)
                .height(AMOUNT_HEIGHT.dp)
                .graphicsLayer {
                    val p = focus.progress.value
                    alpha = p
                    val scale = if (grows) 0.9f + 0.1f * p else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .background(MaterialTheme.colorScheme.surfaceVariant, CapsuleShape)
                .padding(horizontal = 16.dp)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center
        ) {
            ShrinkToFitText(amount, MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold, fontFeatureSettings = TabularFigures),
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private const val LOCKUP_HEIGHT = 28f
private const val AMOUNT_HEIGHT = 44f
private const val GAP = 24f

/**
 * Where focus mode puts the lockup, the code and the amount in the safe area
 * (iOS FocusLayout): the largest code that fits with 16 dp margins and 32 dp
 * above and below, 448 dp at most, the group centered. In dp.
 */
private class FocusLayout(width: Float, height: Float, hasAmount: Boolean) {
    val side: Float
    val lockupCenterY: Float
    val plateCenterY: Float
    val amountCenterY: Float

    init {
        var reserved = LOCKUP_HEIGHT + GAP + 2 * 32f
        if (hasAmount) reserved += GAP + AMOUNT_HEIGHT
        side = max(160f, minOf(width - 32f, 448f, height - reserved))
        var total = LOCKUP_HEIGHT + GAP + side
        if (hasAmount) total += GAP + AMOUNT_HEIGHT
        val top = (height - total) / 2f
        lockupCenterY = top + LOCKUP_HEIGHT / 2f
        plateCenterY = top + LOCKUP_HEIGHT + GAP + side / 2f
        amountCenterY = plateCenterY + side / 2f + GAP + AMOUNT_HEIGHT / 2f
    }
}

private fun lerpRect(from: Rect, to: Rect, p: Float) = Rect(
    from.left + (to.left - from.left) * p,
    from.top + (to.top - from.top) * p,
    from.right + (to.right - from.right) * p,
    from.bottom + (to.bottom - from.bottom) * p
)

/** iOS XMRFormatter.format: 4 to 12 decimals, grouped. */
private fun formatXmr(amount: BigDecimal): String =
    DecimalFormat("#,##0.0000########", DecimalFormatSymbols(Locale.US))
        .apply { roundingMode = RoundingMode.HALF_EVEN }
        .format(amount)

/** Settings > Accessibility > Remove animations: the iOS Reduce Motion. */
private fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * Asks for the display's top refresh rate while focus mode is on screen
 * (Android 15 and later), so the grow runs at 120 Hz where the screen can.
 */
@Composable
private fun HighFrameRate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val view = LocalView.current
    DisposableEffect(view) {
        view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        onDispose { view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT }
    }
}

/**
 * Full screen brightness while [isOn] (iOS FullBrightness). The level eases
 * up as focus mode opens and back down as it closes: a jump in one frame
 * reads as a flash. The old level comes back at once when the app leaves the
 * screen or focus mode goes away.
 */
@Composable
private fun FullBrightness(isOn: Boolean) {
    val window = LocalContext.current.findActivity()?.window ?: return
    val scope = rememberCoroutineScope()
    val brightness = remember(window) { ScreenBrightness(window, scope) }
    LaunchedEffect(isOn) { brightness.setOn(isOn) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, brightness) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> brightness.restore()
                // The user may have set a new level meanwhile: boost saves it again.
                Lifecycle.Event.ON_RESUME -> brightness.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            brightness.restore()
        }
    }
}

private class ScreenBrightness(private val window: Window, private val scope: CoroutineScope) {
    private var isOn = false
    /** The window's own setting and the level before focus mode, saved once per open. */
    private var saved: Pair<Float, Float>? = null
    private var ramp: Job? = null

    fun setOn(on: Boolean) {
        if (on == isOn) return
        isOn = on
        if (on) boost() else dim()
    }

    fun resume() {
        if (isOn) boost()
    }

    private fun boost() {
        if (saved == null) {
            val override = window.attributes.screenBrightness
            saved = override to (if (override >= 0f) override else systemLevel())
        }
        animate(1f, UP_MILLIS)
    }

    private fun dim() {
        val (_, level) = saved ?: return
        animate(level, DOWN_MILLIS)
    }

    /** The saved level at once: the app or focus mode is leaving the screen. */
    fun restore() {
        ramp?.cancel()
        ramp = null
        val (override, _) = saved ?: return
        saved = null
        apply(override)
    }

    private fun animate(target: Float, millis: Long) {
        ramp?.cancel()
        val current = window.attributes.screenBrightness
        val from = if (current >= 0f) current else saved?.second ?: systemLevel()
        if (abs(target - from) <= 0.01f) {
            apply(target)
            finish()
            return
        }
        ramp = scope.launch {
            val start = withFrameNanos { it }
            var applied = 0L
            while (true) {
                val now = withFrameNanos { it }
                val t = min(1f, (now - start) / 1_000_000f / millis)
                // Sixty steps a second look continuous; more only add window updates.
                if (t < 1f && now - applied < STEP_NANOS) continue
                applied = now
                // Smoothstep: eases out of one level and into the next.
                val eased = t * t * (3 - 2 * t)
                apply(from + (target - from) * eased)
                if (t >= 1f) break
            }
            finish()
        }
    }

    /** Back at the saved level: hand the screen back to the system, and save again on the next open. */
    private fun finish() {
        if (isOn) return
        val (override, _) = saved ?: return
        saved = null
        apply(override)
    }

    private fun apply(level: Float) {
        val attributes = window.attributes
        if (attributes.screenBrightness == level) return
        attributes.screenBrightness = level
        window.attributes = attributes
    }

    /** The system level, on the window override's 0 to 1 scale. */
    private fun systemLevel(): Float {
        val level = Settings.System.getInt(window.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return ((level - 1) / 254f).coerceIn(0f, 1f)
    }

    companion object {
        /** Up over about the length of the grow; down ends before the shrink does, so the level is back as the code lands. */
        const val UP_MILLIS = 450L
        const val DOWN_MILLIS = 300L
        const val STEP_NANOS = 15_000_000L
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
