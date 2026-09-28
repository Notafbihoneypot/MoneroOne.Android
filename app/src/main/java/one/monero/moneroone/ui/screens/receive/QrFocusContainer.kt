package one.monero.moneroone.ui.screens.receive

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import one.monero.moneroone.core.locale.tr

/** One cached QR moves from its original bounds into focus and back without a second visible code. */
@Composable
internal fun QrFocusContainer(bitmap: Bitmap?, identity: String, content: @Composable (Modifier, () -> Unit) -> Unit) {
    var expanded by remember(identity) { mutableStateOf(false) }
    var qrBounds by remember { mutableStateOf(Rect.Zero) }
    var rootBounds by remember { mutableStateOf(Rect.Zero) }
    val focus by animateFloatAsState(if (expanded && bitmap != null) 1f else 0f,
        animationSpec = tween(350, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)), label = "qr-focus")
    val overlayVisible = (expanded || focus > 0f) && bitmap != null && qrBounds.width > 0
    BackHandler(expanded) { expanded = false }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootBounds = it.boundsInRoot() }) {
        val density = LocalDensity.current
        val targetSide = with(density) { minOf(maxWidth - 32.dp, maxHeight - 120.dp).toPx() }
        val targetLeft = (constraints.maxWidth - targetSide) / 2f
        val targetTop = (constraints.maxHeight - targetSide) / 2f
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - focus }
            .then(if (overlayVisible) Modifier.clearAndSetSemantics { } else Modifier)) {
            content(Modifier.onGloballyPositioned { qrBounds = it.boundsInRoot() }.alpha(if (overlayVisible) 0f else 1f)) {
                if (bitmap != null && qrBounds.width > 0) expanded = true
            }
        }
        if (overlayVisible) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = focus))
                .clickable { expanded = false }.clearAndSetSemantics { })
            val left = qrBounds.left - rootBounds.left
            val top = qrBounds.top - rootBounds.top
            val side = qrBounds.width + (targetSide - qrBounds.width) * focus
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = tr("QR Code"),
                modifier = Modifier.testTag("qr-focus")
                    .offset { IntOffset((left + (targetLeft - left) * focus).toInt(), (top + (targetTop - top) * focus).toInt()) }
                    .size(with(density) { side.toDp() }).clickable(onClickLabel = tr("Close")) { expanded = false })
        }
    }
}
